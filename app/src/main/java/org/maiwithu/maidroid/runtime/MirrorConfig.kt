package org.maiwithu.maidroid.runtime

import java.net.URI
import java.util.Locale

/** 应用与容器内可配置且支持测速的三类镜像源分类。 */
internal enum class MirrorCategory(
    val title: String,
    val shortLabel: String,
    val subtitle: String,
    val inputLabel: String,
    val inputPlaceholder: String,
    val inputHint: String
) {
    GitHub(
        title = "GitHub 加速镜像",
        shortLabel = "GitHub",
        subtitle = "用于下载 MaiBot、uv、Python、QQ 协议端适配器与应用自更新",
        inputLabel = "自定义 GitHub 加速前缀",
        inputPlaceholder = "https://mirror.example.com",
        inputHint = "填写反代根地址即可（例如 https://ghfast.top），系统会自动拼接目标 GitHub 链接"
    ),
    Apt(
        title = "Debian 软件源",
        shortLabel = "APT",
        subtitle = "用于容器内 apt-get 安装 Git、curl、证书、SSH 与 QQ 运行依赖库",
        inputLabel = "自定义 Debian 镜像根地址",
        inputPlaceholder = "http://mirrors.example.com",
        inputHint = "新容器安装证书前推荐填写 http:// 开头的镜像根地址，其下需包含 /debian 与 /debian-security"
    ),
    PyPi(
        title = "PyPI 依赖镜像",
        shortLabel = "PyPI",
        subtitle = "用于 uv sync / 启动时解析并下载 MaiBot 所需的 Python 依赖包",
        inputLabel = "自定义 PyPI 简单索引地址",
        inputPlaceholder = "https://pypi.example.com/simple",
        inputHint = "填写完整的 /simple 索引地址（https:// 或 http://），支持自建或企业私有源"
    )
}

/** 每一类镜像源的选择策略：自动按测速排名优选、手动锁定预设项或手动输入自定义地址。 */
internal enum class MirrorSelectionMode(val storageValue: String) {
    Auto("auto"),
    Preset("preset"),
    Custom("custom");

    companion object {
        fun fromStorage(raw: String?): MirrorSelectionMode =
            entries.firstOrNull { it.storageValue == raw } ?: Auto
    }
}

/** 单类镜像源的持久化偏好。 */
internal data class MirrorPreference(
    val mode: MirrorSelectionMode = MirrorSelectionMode.Auto,
    val presetValue: String = "",
    val customUrl: String = ""
)

/** 镜像偏好读写抽象，由 AppSettings 实现，同时便于单测在纯 JVM 环境注入内存实现。 */
internal interface MirrorPreferenceStore {
    fun mirrorPreference(category: MirrorCategory): MirrorPreference
    fun saveMirrorPreference(category: MirrorCategory, preference: MirrorPreference)
}

/** 单个候选镜像节点当前测速状态。 */
internal enum class MirrorProbeStatus {
    Idle,
    Testing,
    Reachable,
    Failed
}

/** 单条镜像线路的最近一次测速快照。 */
internal data class MirrorProbeResult(
    val key: String,
    val status: MirrorProbeStatus = MirrorProbeStatus.Idle,
    val latencyMs: Long? = null,
    val errorReason: String? = null,
    val testedAtMs: Long = 0L
)

/** 手动输入自定义镜像地址的格式与安全校验结果。 */
internal sealed interface MirrorUrlValidationResult {
    data class Valid(val normalizedUrl: String) : MirrorUrlValidationResult
    data class Invalid(val reason: String) : MirrorUrlValidationResult
}

private val FORBIDDEN_URL_CHARS = charArrayOf('\'', '"', '`', '$', ';', '|', '&', '<', '>', '\\', '\n', '\r', '\t')

/**
 * 校验并规范化用户手动输入的自定义镜像地址：
 * - 拦住空白与会写入 shell 脚本或配置文件的危险字符；
 * - 强制要求 http:// 或 https:// 且解析得出有效主机名；
 * - 按分类剥离多余尾斜杠或用户误贴的 APT /debian 后缀。
 */
internal fun validateAndNormalizeMirrorUrl(
    category: MirrorCategory,
    raw: String
): MirrorUrlValidationResult {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        return MirrorUrlValidationResult.Invalid("请输入镜像地址")
    }
    if (trimmed.any { it.isWhitespace() || it in FORBIDDEN_URL_CHARS || it.code < 0x20 }) {
        return MirrorUrlValidationResult.Invalid("镜像地址不能包含空格或特殊控制字符")
    }
    val lowerScheme = when {
        trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
        trimmed.startsWith("http://", ignoreCase = true) -> "http://" + trimmed.substring(7)
        else -> return MirrorUrlValidationResult.Invalid("镜像地址必须以 http:// 或 https:// 开头")
    }
    val uri = runCatching { URI(lowerScheme) }.getOrNull()
        ?: return MirrorUrlValidationResult.Invalid("镜像地址格式不正确，请检查域名和路径")
    val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
        ?: return MirrorUrlValidationResult.Invalid("镜像地址缺少有效主机名")
    if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) {
        return MirrorUrlValidationResult.Invalid("请不要在镜像地址中附带查询参数、# 片段或用户名密码")
    }

    val normalizedPort = if (uri.port > 0) ":${uri.port}" else ""
    var cleanPath = uri.rawPath.orEmpty().replace(Regex("/+"), "/").trimEnd('/')
    if (category == MirrorCategory.GitHub && (host == "github.com" || host == "www.github.com") && cleanPath.isEmpty()) {
        return MirrorUrlValidationResult.Invalid("如需直连 GitHub，请在上方预设中直接选择“${GitHubMirrors.DIRECT_NAME}”")
    }
    if (category == MirrorCategory.Apt) {
        while (cleanPath.endsWith("/debian-security") || cleanPath.endsWith("/debian")) {
            cleanPath = when {
                cleanPath.endsWith("/debian-security") -> cleanPath.removeSuffix("/debian-security")
                else -> cleanPath.removeSuffix("/debian")
            }.trimEnd('/')
        }
    }
    val normalized = "${uri.scheme.lowercase(Locale.ROOT)}://$host$normalizedPort$cleanPath"
    return MirrorUrlValidationResult.Valid(normalized)
}

/** 将候选列表按统一测速/手动优先级策略排序，确保用户选择或最快节点排在首位，其余可达节点紧跟兜底。 */
private fun <T> orderMirrors(
    all: List<T>,
    keyOf: (T) -> String,
    customFactory: (String) -> T,
    category: MirrorCategory,
    preference: MirrorPreference,
    probes: Map<String, MirrorProbeResult>
): List<T> {
    fun priorityRank(item: T): Pair<Int, Long> {
        val probe = probes[keyOf(item)]
        return when (probe?.status) {
            MirrorProbeStatus.Reachable -> 0 to (probe.latencyMs ?: Long.MAX_VALUE)
            MirrorProbeStatus.Failed -> 2 to Long.MAX_VALUE
            MirrorProbeStatus.Idle,
            MirrorProbeStatus.Testing,
            null -> 1 to Long.MAX_VALUE
        }
    }

    val speedSorted = all.sortedWith { a, b ->
        val ra = priorityRank(a)
        val rb = priorityRank(b)
        val groupCmp = ra.first.compareTo(rb.first)
        if (groupCmp != 0) groupCmp else ra.second.compareTo(rb.second)
    }

    return when (preference.mode) {
        MirrorSelectionMode.Auto -> speedSorted
        MirrorSelectionMode.Preset -> {
            val chosen = all.firstOrNull { keyOf(it) == preference.presetValue }
                ?: return speedSorted
            listOf(chosen) + speedSorted.filterNot { keyOf(it) == preference.presetValue }
        }
        MirrorSelectionMode.Custom -> {
            val valid = validateAndNormalizeMirrorUrl(category, preference.customUrl) as? MirrorUrlValidationResult.Valid
                ?: return speedSorted
            val customKey = valid.normalizedUrl
            val customItem = customFactory(customKey)
            listOf(customItem) + speedSorted.filterNot { keyOf(it) == customKey }
        }
    }
}

/** 根据测速结果与用户配置给出有序 Debian APT 镜像列表（首项为主源，其余作失效兜底）。 */
internal fun orderAptMirrors(
    preference: MirrorPreference,
    probes: Map<String, MirrorProbeResult> = emptyMap()
): List<AptMirror> = orderMirrors(
    all = AptMirrors.ALL,
    keyOf = AptMirror::baseUrl,
    customFactory = { url -> AptMirror("自定义软件源", url) },
    category = MirrorCategory.Apt,
    preference = preference,
    probes = probes
)

/** 根据测速结果与用户配置给出有序 GitHub 下载线路列表。 */
internal fun orderGitHubMirrors(
    preference: MirrorPreference,
    probes: Map<String, MirrorProbeResult> = emptyMap()
): List<GitHubMirror> = orderMirrors(
    all = GitHubMirrors.ALL,
    keyOf = GitHubMirror::prefix,
    customFactory = { url -> GitHubMirror("自定义镜像", url) },
    category = MirrorCategory.GitHub,
    preference = preference,
    probes = probes
)

/** 根据测速结果与用户配置给出有序 PyPI / uv 索引列表。 */
internal fun orderPyPiMirrors(
    preference: MirrorPreference,
    probes: Map<String, MirrorProbeResult> = emptyMap()
): List<PyPiMirror> = orderMirrors(
    all = PyPiMirrors.ALL,
    keyOf = PyPiMirror::indexUrl,
    customFactory = { url -> PyPiMirror("自定义 PyPI 源", url) },
    category = MirrorCategory.PyPi,
    preference = preference,
    probes = probes
)
