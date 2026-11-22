package org.maiwithu.maidroid.maibot

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CancellationException
import org.maiwithu.maidroid.runtime.InContainerPaths
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File

/**
 * 宿主注册表（host/registry.sqlite3）里登记的一个 Bot 实例原始行。
 */
internal data class MaiBotRegistryRow(
    val botId: String,
    val name: String,
    val directory: String,
    val initialization: String,
    val desiredState: String
)

/**
 * 容器里一个可用的 MaiBot 运行实例：可能是 1.4.x 新装用户落在 `bots/<id>/` 下的独立实例，
 * 也可能是老用户原地升级或旧单实例版本留下的 `/opt/MaiBot` 本体。
 * 所有按实例隔离的资源（第三方插件、配置、运行日志、temp/worker-console.log）都应从这里取值，不再写死根目录。
 */
internal data class MaiBotInstance(
    val dir: File,
    val containerDir: String,
    val botId: String? = null,
    val name: String = "MaiBot",
    val initialized: Boolean = true,
    val desiredRunning: Boolean = true
) {
    val pluginsDir: File get() = File(dir, "plugins")
    val containerPluginsDir: String get() = "${containerDir.trimEnd('/')}/plugins"
    val configDir: File get() = File(dir, "config")
    val logsDir: File get() = File(dir, "logs")
    val tempDir: File get() = File(dir, "temp")
    val workerConsoleLog: File get() = File(tempDir, "worker-console.log")
}

// 1.4.x 全新工作区的默认实例目录是 32 位小写十六进制 uuid4().hex（见上游 src/host/registry.py）
private val BOT_ID_DIR_REGEX = Regex("""^[0-9a-f]{32}$""")
private val HOST_REGISTRY_REL = "host/registry.sqlite3"
private val SHARED_MODEL_CATALOG_REL = "host/model_catalog.toml"

/**
 * 从当前容器目录发现活跃的 MaiBot 实例列表（保持与上游一致的优先级与顺序）：
 * 1. 注册表 host/registry.sqlite3 存在且读得出有效记录 -> 按 `created_at, bot_id` 顺序解析各登记目录；
 * 2. 注册表读失败或缺失，但存在 `bots/<32位hex>/`（且根目录不是已初始化的旧单实例）-> 扫描 `bots/` 兜底；
 * 3. 兼容旧单实例版本或尚在初始化前 -> 回退到 `/opt/MaiBot` 本身。
 */
internal fun discoverMaiBotInstances(
    maiBotDir: File,
    rootfsDir: File,
    containerMaiBotDir: String = InContainerPaths.MAIBOT_DIR,
    registryReader: (File) -> List<MaiBotRegistryRow>? = ::readMaiBotRegistrySqlite
): List<MaiBotInstance> {
    val normalizedContainerRoot = normalizeContainerRoot(containerMaiBotDir)
    val registryFile = File(maiBotDir, HOST_REGISTRY_REL)
    if (registryFile.isFile) {
        val rows = registryReader(registryFile)
        if (!rows.isNullOrEmpty()) {
            val resolved = resolveRegistryRows(rows, maiBotDir, rootfsDir, normalizedContainerRoot)
            if (resolved.isNotEmpty()) return resolved
        }
    }

    // 注册表因只读异常、锁或版本变化读不出时，如果工作区明显属于 1.4.x 全新多实例布局，按 bots/ 目录名兜底识别。
    val botsDir = File(maiBotDir, "bots")
    val hasRootConfig = File(maiBotDir, "config/bot_config.toml").isFile
    if (botsDir.isDirectory && (registryFile.isFile || !hasRootConfig)) {
        val scanned = scanBotsDirectory(botsDir, normalizedContainerRoot)
        if (scanned.isNotEmpty()) return scanned
    }

    return listOf(MaiBotInstance(dir = maiBotDir, containerDir = normalizedContainerRoot))
}

/**
 * 为适配器这类插件挑选目标实例：
 * 已有该插件的实例优先（避免双份冲突或迁错实例），其中又以已完成初始化且设为自动启动的优先；
 * 还没在任何实例装过时，按注册表顺序选第一个已初始化且设为启动的实例，其次第一个已初始化实例。
 */
internal fun resolvePluginTargetInstance(
    instances: List<MaiBotInstance>,
    pluginId: String,
    preferredDirName: String = "",
    fallback: MaiBotInstance
): MaiBotInstance {
    val installed = instances.filter { findPluginDirById(it.pluginsDir, pluginId, preferredDirName) != null }
    if (installed.isNotEmpty()) {
        return installed.firstOrNull { it.initialized && it.desiredRunning }
            ?: installed.firstOrNull { it.initialized }
            ?: installed.first()
    }
    return instances.firstOrNull { it.initialized && it.desiredRunning }
        ?: instances.firstOrNull { it.initialized }
        ?: instances.firstOrNull()
        ?: fallback
}

/** 面向 Android 运行时路径的多实例发现与模型目录入口。 */
internal object MaiBotInstances {
    fun discover(paths: RuntimePaths): List<MaiBotInstance> =
        discoverMaiBotInstances(paths.maiBotDir, paths.rootfsDir, InContainerPaths.MAIBOT_DIR)

    fun resolvePluginTarget(
        paths: RuntimePaths,
        pluginId: String,
        preferredDirName: String = ""
    ): MaiBotInstance {
        val fallback = MaiBotInstance(dir = paths.maiBotDir, containerDir = InContainerPaths.MAIBOT_DIR)
        return resolvePluginTargetInstance(discover(paths), pluginId, preferredDirName, fallback)
    }

    /** 1.4.x 多实例共享的模型供应商与密钥目录（host/model_catalog.toml），供脱敏收集共用 API Key。 */
    fun sharedModelCatalogFile(paths: RuntimePaths): File = File(paths.maiBotDir, SHARED_MODEL_CATALOG_REL)
}

private fun resolveRegistryRows(
    rows: List<MaiBotRegistryRow>,
    maiBotDir: File,
    rootfsDir: File,
    containerMaiBotDir: String
): List<MaiBotInstance> {
    val seenContainerDirs = HashSet<String>()
    val result = mutableListOf<MaiBotInstance>()
    for (row in rows) {
        val (dir, containerDir) = resolveBotDirectory(
            rawDirectory = row.directory,
            maiBotDir = maiBotDir,
            rootfsDir = rootfsDir,
            containerMaiBotDir = containerMaiBotDir
        ) ?: continue
        if (!seenContainerDirs.add(containerDir)) continue
        result.add(
            MaiBotInstance(
                dir = dir,
                containerDir = containerDir,
                botId = row.botId,
                name = row.name.ifBlank { "MaiBot" },
                initialized = row.initialization == "initialized",
                desiredRunning = row.desiredState == "running"
            )
        )
    }
    return result
}

private fun scanBotsDirectory(botsDir: File, containerMaiBotDir: String): List<MaiBotInstance> {
    val candidates = botsDir.listFiles { file ->
        file.isDirectory && BOT_ID_DIR_REGEX.matches(file.name)
    }?.sortedBy { it.name }.orEmpty()
    if (candidates.isEmpty()) return emptyList()

    // 优先挑出已完成初始化或已经有插件目录的实例；如果刚创建目录还没来得及写配置，也保留全部合法候选目录。
    val readyCandidates = candidates.filter { dir ->
        File(dir, "config/bot_config.toml").isFile || File(dir, "plugins").isDirectory
    }
    val chosen = readyCandidates.ifEmpty { candidates }
    return chosen.map { dir ->
        MaiBotInstance(
            dir = dir,
            containerDir = "${containerMaiBotDir.trimEnd('/')}/bots/${dir.name}",
            botId = dir.name,
            name = dir.name
        )
    }
}

private fun resolveBotDirectory(
    rawDirectory: String,
    maiBotDir: File,
    rootfsDir: File,
    containerMaiBotDir: String
): Pair<File, String>? {
    val trimmed = rawDirectory.trim().replace('\\', '/')
    if (trimmed.isEmpty() || trimmed == ".") {
        return maiBotDir to containerMaiBotDir
    }
    if (trimmed.startsWith("/")) {
        val segments = normalizeRelativeSegments(trimmed.removePrefix("/")) ?: return null
        if (segments.isEmpty()) return rootfsDir to "/"
        val relativeToRoot = segments.joinToString("/")
        return File(rootfsDir, relativeToRoot) to "/$relativeToRoot"
    }
    val segments = normalizeRelativeSegments(trimmed) ?: return null
    if (segments.isEmpty()) return maiBotDir to containerMaiBotDir
    val relativeToWorkspace = segments.joinToString("/")
    return File(maiBotDir, relativeToWorkspace) to "$containerMaiBotDir/$relativeToWorkspace"
}

// 规范化 POSIX 相对路径片段，出现试图上穿边界的 `..` 直接返回 null 过滤掉非法路径。
private fun normalizeRelativeSegments(relativePath: String): List<String>? {
    val stack = ArrayList<String>()
    for (part in relativePath.split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> {
                if (stack.isEmpty()) return null
                stack.removeAt(stack.lastIndex)
            }
            else -> stack.add(part)
        }
    }
    return stack
}

private fun normalizeContainerRoot(containerMaiBotDir: String): String {
    val trimmed = containerMaiBotDir.trim().trimEnd('/')
    return if (trimmed.isEmpty()) "/" else trimmed
}

private fun readMaiBotRegistrySqlite(registryFile: File): List<MaiBotRegistryRow>? =
    try {
        // 用只读 + NO_LOCALIZED_COLLATORS 打开上游 SQLite，避免触发 android_metadata 写入或破坏租约。
        SQLiteDatabase.openDatabase(
            registryFile.path,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { db ->
            db.rawQuery(
                "SELECT bot_id, name, directory, initialization, desired_state FROM bots ORDER BY created_at, bot_id",
                null
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val botId = cursor.getString(0) ?: continue
                        val name = cursor.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "MaiBot"
                        val directory = cursor.getString(2) ?: continue
                        val initialization = cursor.getString(3).orEmpty()
                        val desiredState = cursor.getString(4).orEmpty()
                        add(MaiBotRegistryRow(botId, name, directory, initialization, desiredState))
                    }
                }
            }
        }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        null
    }
