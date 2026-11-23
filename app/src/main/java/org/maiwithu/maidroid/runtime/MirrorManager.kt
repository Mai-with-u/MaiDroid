package org.maiwithu.maidroid.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.terminal.TerminalLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** 镜像源管理页面与 OOBE 弹窗共用的响应式状态快照。 */
internal data class MirrorSettingsState(
    val preferences: Map<MirrorCategory, MirrorPreference> =
        MirrorCategory.entries.associateWith { MirrorPreference() },
    val probes: Map<MirrorCategory, Map<String, MirrorProbeResult>> =
        MirrorCategory.entries.associateWith { emptyMap() },
    val testing: Map<MirrorCategory, Boolean> =
        MirrorCategory.entries.associateWith { false },
    val customProbes: Map<MirrorCategory, MirrorProbeResult?> =
        MirrorCategory.entries.associateWith { null }
)

/**
 * 全应用统一的镜像源协调器：
 * - 维护 GitHub / Debian APT / PyPI 三类镜像源配置、10 分钟测速缓存与单类互斥锁；
 * - 自动模式下首次使用或点“测速”时并发探测延迟并择优排序；
 * - 手动指定预设或自定义镜像地址后，立即持久化并在容器目录已就绪时同步宿主侧 `sources.list` 与 `pypi-index`。
 */
internal class MirrorManager(
    private val store: MirrorPreferenceStore,
    private val rootfsDir: File,
    private val speedTester: MirrorSpeedTester = MirrorSpeedTester(),
    private val probeCategory: suspend (
        MirrorCategory,
        String?,
        (MirrorProbeResult) -> Unit
    ) -> Map<String, MirrorProbeResult> = { category, extraCustom, onResult ->
        speedTester.probeCategory(category, extraCustom, onResult)
    },
    private val probeCustom: suspend (MirrorCategory, String) -> MirrorProbeResult = { category, url ->
        speedTester.probeCustomUrl(category, url)
    },
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
    private val logLine: (String) -> Unit = TerminalLog::append,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private companion object {
        const val CACHE_TTL_MS = 10 * 60_000L
        const val PYPI_INDEX_REL_PATH = "root/.maidroid/pypi-index"
        const val APT_SOURCES_REL_PATH = "etc/apt/sources.list"
        const val OS_RELEASE_REL_PATH = "etc/os-release"
    }

    private val syncScope = CoroutineScope(ioDispatcher + SupervisorJob())
    private val categoryMutexes = MirrorCategory.entries.associateWith { Mutex() }
    private val fileSyncMutexes = MirrorCategory.entries.associateWith { Mutex() }
    private val lastTestedAtMs = ConcurrentHashMap<MirrorCategory, Long>()

    private val _state = MutableStateFlow(
        MirrorSettingsState(
            preferences = MirrorCategory.entries.associateWith { store.mirrorPreference(it) }
        )
    )
    val state: StateFlow<MirrorSettingsState> = _state.asStateFlow()

    /** 是否已存在过任意一次完整测速记录（设置页首次打开时据此决定是否自动测速）。 */
    fun hasAnyTestedCache(): Boolean = lastTestedAtMs.isNotEmpty()

    /** 该分类是否由用户手动锁定预设或自定义镜像。 */
    fun isManualSelection(category: MirrorCategory): Boolean =
        (_state.value.preferences[category]?.mode ?: MirrorSelectionMode.Auto) != MirrorSelectionMode.Auto

    /** 按当前内存快照返回有序 GitHub 线路，不发起网络测速，供宿主侧同步调用。 */
    fun currentGitHubMirrors(): List<GitHubMirror> =
        orderedMirrorsFor(MirrorCategory.GitHub, ::orderGitHubMirrors)

    /** 并发测速三类全部镜像源。 */
    suspend fun testAll() = coroutineScope {
        MirrorCategory.entries.forEach { category ->
            launch { testCategory(category, force = true) }
        }
    }

    /** 对单个分类执行并发测速；`force = false` 时 10 分钟缓存内直接跳过。 */
    suspend fun testCategory(category: MirrorCategory, force: Boolean = true) {
        categoryMutexes.getValue(category).withLock {
            if (!force && isCacheFresh(category)) return
            val pref = _state.value.preferences.getValue(category)
            val savedCustom = pref.customUrl.takeIf { it.isNotBlank() }
            val targets = speedTester.targetsFor(category, savedCustom)
            _state.update { current ->
                val resetProbes = current.probes.getValue(category).toMutableMap().apply {
                    targets.forEach { target ->
                        this[target.key] = MirrorProbeResult(
                            key = target.key,
                            status = MirrorProbeStatus.Testing
                        )
                    }
                }
                current.copy(
                    testing = current.testing + (category to true),
                    probes = current.probes + (category to resetProbes)
                )
            }

            try {
                val results = probeCategory(category, savedCustom) { result ->
                    _state.update { current ->
                        val nextMap = current.probes.getValue(category) + (result.key to result)
                        val nextCustomProbe = if (savedCustom == result.key) result else current.customProbes[category]
                        current.copy(
                            probes = current.probes + (category to nextMap),
                            customProbes = current.customProbes + (category to nextCustomProbe)
                        )
                    }
                }
                _state.update { current ->
                    val merged = current.probes.getValue(category) + results
                    current.copy(probes = current.probes + (category to merged))
                }
                lastTestedAtMs[category] = nowEpochMs()
                logProbeSummary(category, results)
                if (category == MirrorCategory.Apt || category == MirrorCategory.PyPi) {
                    syncContainerMirrorFile(category)
                }
            } finally {
                _state.update { current ->
                    current.copy(testing = current.testing + (category to false))
                }
            }
        }
    }

    /** 对输入框中的自定义镜像地址执行合法性校验与单独测速；不修改当前激活设置。 */
    suspend fun testCustomUrl(category: MirrorCategory, rawUrl: String): String? {
        val valid = when (val parsed = validateAndNormalizeMirrorUrl(category, rawUrl)) {
            is MirrorUrlValidationResult.Invalid -> return parsed.reason
            is MirrorUrlValidationResult.Valid -> parsed.normalizedUrl
        }
        _state.update { current ->
            current.copy(
                customProbes = current.customProbes + (
                    category to MirrorProbeResult(key = valid, status = MirrorProbeStatus.Testing)
                    )
            )
        }
        val result = probeCustom(category, valid)
        _state.update { current ->
            val currentPref = current.preferences.getValue(category)
            val nextCategoryProbes = if (currentPref.customUrl == valid) {
                current.probes.getValue(category) + (valid to result)
            } else {
                current.probes.getValue(category)
            }
            current.copy(
                customProbes = current.customProbes + (category to result),
                probes = current.probes + (category to nextCategoryProbes)
            )
        }
        return null
    }

    /** 切换某分类为“自动测速优选”。 */
    fun selectAuto(category: MirrorCategory) {
        val prevCustom = _state.value.preferences.getValue(category).customUrl
        val nextPref = MirrorPreference(
            mode = MirrorSelectionMode.Auto,
            presetValue = "",
            customUrl = prevCustom
        )
        persistPreference(category, nextPref)
    }

    /** 切换某分类为指定内置预设节点。 */
    fun selectPreset(category: MirrorCategory, presetValue: String) {
        val prevCustom = _state.value.preferences.getValue(category).customUrl
        val nextPref = MirrorPreference(
            mode = MirrorSelectionMode.Preset,
            presetValue = presetValue,
            customUrl = prevCustom
        )
        persistPreference(category, nextPref)
    }

    /** 校验、保存并立即启用用户手动输入的自定义镜像地址；校验失败返回中文说明，成功返回 null。 */
    fun saveCustomUrl(category: MirrorCategory, rawUrl: String): String? {
        val valid = when (val parsed = validateAndNormalizeMirrorUrl(category, rawUrl)) {
            is MirrorUrlValidationResult.Invalid -> return parsed.reason
            is MirrorUrlValidationResult.Valid -> parsed.normalizedUrl
        }
        val nextPref = MirrorPreference(
            mode = MirrorSelectionMode.Custom,
            presetValue = "",
            customUrl = valid
        )
        persistPreference(category, nextPref)
        return null
    }

    /** 解析有序 Debian APT 镜像列表；Auto 且缓存过期时先测速，并按需同步宿主侧 `/etc/apt/sources.list`。 */
    suspend fun resolveAptMirrors(allowSpeedTest: Boolean = true): List<AptMirror> {
        ensureAutoTested(MirrorCategory.Apt, allowSpeedTest)
        val ordered = orderedMirrorsFor(MirrorCategory.Apt, ::orderAptMirrors)
        syncContainerMirrorFile(MirrorCategory.Apt)
        return ordered
    }

    /** 解析有序 GitHub 加速镜像列表；Auto 且缓存过期时先测速。 */
    suspend fun resolveGitHubMirrors(allowSpeedTest: Boolean = true): List<GitHubMirror> {
        ensureAutoTested(MirrorCategory.GitHub, allowSpeedTest)
        return orderedMirrorsFor(MirrorCategory.GitHub, ::orderGitHubMirrors)
    }

    /** 解析有序 PyPI / uv 索引列表；Auto 且缓存过期时先测速，并按需同步 `/root/.maidroid/pypi-index`。 */
    suspend fun resolvePyPiMirrors(allowSpeedTest: Boolean = true): List<PyPiMirror> {
        ensureAutoTested(MirrorCategory.PyPi, allowSpeedTest)
        val ordered = orderedMirrorsFor(MirrorCategory.PyPi, ::orderPyPiMirrors)
        syncContainerMirrorFile(MirrorCategory.PyPi)
        return ordered
    }

    /** 把最新的 APT 或 PyPI 首选镜像原子同步进已解包的容器配置文件；加按分类互斥锁防止并发写临时文件互踩。 */
    internal suspend fun syncContainerMirrorFile(category: MirrorCategory) = withContext(ioDispatcher) {
        fileSyncMutexes.getValue(category).withLock {
            when (category) {
                MirrorCategory.GitHub -> Unit
                MirrorCategory.Apt -> syncAptSourcesList()
                MirrorCategory.PyPi -> syncPyPiIndexFile()
            }
        }
    }

    private fun syncAptSourcesList() {
        val aptDir = File(rootfsDir, APT_SOURCES_REL_PATH).parentFile ?: return
        if (!aptDir.isDirectory) return
        val preferred = orderedMirrorsFor(MirrorCategory.Apt, ::orderAptMirrors).firstOrNull() ?: return
        val codename = readDebianCodename()
        val desired = AptMirrors.sourcesListContent(preferred.baseUrl, codename)
        val target = File(aptDir, "sources.list")
        if (target.isFile && runCatching { target.readText() == desired }.getOrDefault(false)) return
        runCatching { target.writeTextAtomically(desired) }
    }

    private fun syncPyPiIndexFile() {
        val rootDir = File(rootfsDir, "root")
        if (!rootDir.isDirectory) return
        val target = File(rootfsDir, PYPI_INDEX_REL_PATH)
        val preferred = orderedMirrorsFor(MirrorCategory.PyPi, ::orderPyPiMirrors).firstOrNull() ?: return
        if (preferred.isBuiltInDefault) {
            if (target.isFile) runCatching { target.delete() }
            return
        }
        val desired = "${preferred.indexUrl}\n"
        if (target.isFile && runCatching { target.readText() == desired }.getOrDefault(false)) return
        runCatching {
            target.parentFile?.mkdirs()
            target.writeTextAtomically(desired)
        }
    }

    private fun readDebianCodename(): String {
        val osRelease = File(rootfsDir, OS_RELEASE_REL_PATH)
        if (!osRelease.isFile) return "bookworm"
        return runCatching {
            osRelease.useLines { lines ->
                lines.firstNotNullOfOrNull { line ->
                    if (line.startsWith("VERSION_CODENAME=")) {
                        line.removePrefix("VERSION_CODENAME=").trim().trim('"', '\'').ifBlank { null }
                    } else {
                        null
                    }
                }
            }
        }.getOrNull() ?: "bookworm"
    }

    private suspend fun ensureAutoTested(category: MirrorCategory, allowSpeedTest: Boolean) {
        val pref = _state.value.preferences.getValue(category)
        if (allowSpeedTest && pref.mode == MirrorSelectionMode.Auto && !isCacheFresh(category)) {
            testCategory(category, force = false)
        }
    }

    private fun persistPreference(category: MirrorCategory, preference: MirrorPreference) {
        store.saveMirrorPreference(category, preference)
        _state.update { current ->
            current.copy(preferences = current.preferences + (category to preference))
        }
        if (category == MirrorCategory.Apt || category == MirrorCategory.PyPi) {
            syncScope.launch { syncContainerMirrorFile(category) }
        }
    }

    private fun <T> orderedMirrorsFor(
        category: MirrorCategory,
        sorter: (MirrorPreference, Map<String, MirrorProbeResult>) -> List<T>
    ): List<T> {
        val snapshot = _state.value
        return sorter(snapshot.preferences.getValue(category), snapshot.probes.getValue(category))
    }

    private fun isCacheFresh(category: MirrorCategory): Boolean {
        val lastAt = lastTestedAtMs[category] ?: return false
        return nowEpochMs() - lastAt < CACHE_TTL_MS
    }

    private fun logProbeSummary(category: MirrorCategory, results: Map<String, MirrorProbeResult>) {
        val targetsByKey = speedTester.targetsFor(category, _state.value.preferences.getValue(category).customUrl)
            .associateBy { it.key }
        val reachable = results.values
            .filter { it.status == MirrorProbeStatus.Reachable && it.latencyMs != null }
            .sortedBy { it.latencyMs }
        if (reachable.isEmpty()) {
            logLine("[镜像源] ${category.title}测速完成：当前候选均不可达，将沿用默认兜底顺序")
            return
        }
        val topSummary = reachable.take(3).joinToString("，") { probe ->
            val label = targetsByKey[probe.key]?.displayName ?: probe.key
            "$label ${probe.latencyMs}ms"
        }
        logLine("[镜像源] ${category.title}测速完成（$topSummary）")
    }
}
