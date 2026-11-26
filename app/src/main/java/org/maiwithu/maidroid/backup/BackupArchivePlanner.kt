package org.maiwithu.maidroid.backup

import org.maiwithu.maidroid.maibot.MaiBotInstance
import org.maiwithu.maidroid.maibot.MaiBotInstances
import org.maiwithu.maidroid.platform.napcat.NapCatFiles
import org.maiwithu.maidroid.platform.qq.QqOneBot
import org.maiwithu.maidroid.platform.snowluma.SnowLumaFiles
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File
import java.nio.file.Files

internal data class PlannedBackupEntry(
    val sourceFile: File,
    val archivePath: String,
    val component: BackupComponent
)

/**
 * 扫描并筛选各组件应纳入备份的文件清单：
 * 覆盖 MaiBot 宿主与全部实例的配置、记忆库、插件，以及 SnowLuma、NapCat 配置；
 * 自动排除锁文件、运行缓存、历史旧配置、符号链接和可由 SQLite 快照恢复的旁路日志。
 */
internal class BackupArchivePlanner(
    private val paths: RuntimePaths,
    private val instancesProvider: () -> List<MaiBotInstance> = { MaiBotInstances.discover(paths) }
) {
    private companion object {
        val PLUGIN_EXCLUDED_DIRS = setOf(".git", "__pycache__", ".venv", ".update_backups", "node_modules")
        val PLUGIN_EXCLUDED_SUFFIXES = listOf(".pyc", ".pyo", ".lock")
        val SQLITE_SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")
        val MEMORY_EXCLUDED_FILES = setOf(".a_memorix_runtime_writer.lock")
    }

    fun plan(components: Set<BackupComponent>): List<PlannedBackupEntry> {
        if (components.isEmpty()) return emptyList()
        val entries = mutableListOf<PlannedBackupEntry>()
        val seenArchivePaths = LinkedHashSet<String>()

        fun addFile(file: File, archivePath: String, component: BackupComponent) {
            if (!isRegularNonLinkFile(file)) return
            if (seenArchivePaths.add(archivePath)) {
                entries += PlannedBackupEntry(file, archivePath, component)
            }
        }

        // MaiBot 配置与记忆库任意一项启用时都要带上实例注册表，保证多实例 ID 与目录映射完整。
        if (BackupComponent.MaiBotConfig in components || BackupComponent.MaiBotMemory in components) {
            addFile(File(paths.maiBotDir, "host/registry.sqlite3"), "maibot/host/registry.sqlite3", BackupComponent.MaiBotConfig)
        }
        if (BackupComponent.MaiBotConfig in components) {
            addFile(File(paths.maiBotDir, "host/model_catalog.toml"), "maibot/host/model_catalog.toml", BackupComponent.MaiBotConfig)
            addFile(File(paths.maiBotDir, "host/config.toml"), "maibot/host/config.toml", BackupComponent.MaiBotConfig)
            addFile(File(paths.maiBotDir, "host/webui.json"), "maibot/host/webui.json", BackupComponent.MaiBotConfig)
        }

        // 逐一扫描当前已登记或兜底发现的所有 MaiBot 实例。
        if (components.any { it == BackupComponent.MaiBotConfig || it == BackupComponent.MaiBotMemory || it == BackupComponent.Plugins }) {
            for (instance in instancesProvider()) {
                val instanceRoot = instance.botId?.let { "maibot/bots/$it" } ?: "maibot/root"
                if (BackupComponent.MaiBotConfig in components) {
                    collectDirectory(
                        root = instance.configDir,
                        archivePrefix = "$instanceRoot/config",
                        component = BackupComponent.MaiBotConfig,
                        skipDir = { dir -> dir.name == "old" },
                        skipFile = { file -> file.name.endsWith(".lock") },
                        onFile = ::addFile
                    )
                    // 兼容旧单实例版本：WebUI 凭据可能放在实例的 data/webui.json 里。
                    addFile(File(instance.dir, "data/webui.json"), "$instanceRoot/data/webui.json", BackupComponent.MaiBotConfig)
                }
                if (BackupComponent.MaiBotMemory in components) {
                    collectDirectory(
                        root = File(instance.dir, "data"),
                        archivePrefix = "$instanceRoot/data",
                        component = BackupComponent.MaiBotMemory,
                        skipDir = { dir -> dir.name == "prompt_imgs" },
                        skipFile = { file -> shouldSkipMemoryFile(file) },
                        onFile = ::addFile
                    )
                }
                if (BackupComponent.Plugins in components) {
                    collectDirectory(
                        root = instance.pluginsDir,
                        archivePrefix = "$instanceRoot/plugins",
                        component = BackupComponent.Plugins,
                        skipDir = { dir -> dir.name in PLUGIN_EXCLUDED_DIRS },
                        skipFile = { file -> PLUGIN_EXCLUDED_SUFFIXES.any { file.name.endsWith(it) } },
                        onFile = ::addFile
                    )
                }
            }
        }

        if (BackupComponent.SnowLuma in components) {
            val snowLuma = SnowLumaFiles(paths)
            collectDirectory(
                root = snowLuma.configDir,
                archivePrefix = "snowluma/config",
                component = BackupComponent.SnowLuma,
                skipDir = { false },
                skipFile = { file -> file.name.endsWith(".lock") },
                onFile = ::addFile
            )
            addFile(snowLuma.webUiPasswordFile, "snowluma/meta/snowluma_webui_password", BackupComponent.SnowLuma)
            addFile(snowLuma.consentMarker, "snowluma/meta/snowluma_consent", BackupComponent.SnowLuma)
            addFile(snowLuma.readyMarker, "snowluma/meta/snowluma_ready", BackupComponent.SnowLuma)
            addSharedQqControlFiles(BackupComponent.SnowLuma, ::addFile)
        }

        if (BackupComponent.NapCat in components) {
            val napCat = NapCatFiles(paths)
            collectDirectory(
                root = napCat.configDir,
                archivePrefix = "napcat/config",
                component = BackupComponent.NapCat,
                skipDir = { false },
                skipFile = { file -> file.name.endsWith(".lock") },
                onFile = ::addFile
            )
            addFile(napCat.readyMarker, "napcat/meta/napcat_ready", BackupComponent.NapCat)
            addSharedQqControlFiles(BackupComponent.NapCat, ::addFile)
        }

        return entries
    }

    private fun addSharedQqControlFiles(
        component: BackupComponent,
        addFile: (File, String, BackupComponent) -> Unit
    ) {
        addFile(File(paths.rootfsDir, "root/.maidroid/qq_client"), "shared/qq_client", component)
        addFile(File(paths.rootfsDir, QqOneBot.TOKEN_PATH.removePrefix("/")), "shared/napcat_token", component)
    }

    private fun shouldSkipMemoryFile(file: File): Boolean {
        val name = file.name
        if (name in MEMORY_EXCLUDED_FILES || name.endsWith(".lock")) return true
        // SQLite 的旁路文件若存在对应主数据库，交给打包器在做在线快照失败时再决定是否按需附带。
        val matchingBase = SQLITE_SIDECAR_SUFFIXES.firstOrNull { name.endsWith(it) }
            ?.let { suffix -> File(file.parentFile, name.removeSuffix(suffix)) }
        return matchingBase?.isFile == true
    }

    private fun collectDirectory(
        root: File,
        archivePrefix: String,
        component: BackupComponent,
        skipDir: (File) -> Boolean,
        skipFile: (File) -> Boolean,
        onFile: (File, String, BackupComponent) -> Unit
    ) {
        if (!root.isDirectory || isSymlink(root)) return
        val stack = ArrayDeque<Pair<File, String>>()
        stack.addLast(root to archivePrefix)
        while (stack.isNotEmpty()) {
            val (dir, relPrefix) = stack.removeLast()
            val children = dir.listFiles()?.sortedBy(File::getName).orEmpty()
            for (child in children) {
                if (isSymlink(child)) continue
                val childArchivePath = "$relPrefix/${child.name}"
                if (child.isDirectory) {
                    if (!skipDir(child)) {
                        stack.addLast(child to childArchivePath)
                    }
                } else if (child.isFile && !skipFile(child)) {
                    onFile(child, childArchivePath, component)
                }
            }
        }
    }

    private fun isRegularNonLinkFile(file: File): Boolean =
        file.isFile && !isSymlink(file)

    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
}
