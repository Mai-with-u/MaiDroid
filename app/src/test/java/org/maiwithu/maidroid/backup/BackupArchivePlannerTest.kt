package org.maiwithu.maidroid.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.maiwithu.maidroid.maibot.MaiBotInstance
import org.maiwithu.maidroid.runtime.RuntimePaths
import java.io.File
import java.nio.file.Files

class BackupArchivePlannerTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `planner collects host instances memory plugins and deduplicates shared files`() {
        val filesDir = tempFolder.newFolder("files")
        val libsDir = tempFolder.newFolder("libs")
        val paths = RuntimePaths(appFilesDir = filesDir, nativeLibraryDir = libsDir)

        // MaiBot host 文件
        writeFile(File(paths.maiBotDir, "host/registry.sqlite3"), "reg")
        writeFile(File(paths.maiBotDir, "host/model_catalog.toml"), "models")
        writeFile(File(paths.maiBotDir, "host/webui.json"), "webui")

        // 实例 1（1.4.x bots/<id>）
        val botId = "0123456789abcdef0123456789abcdef"
        val botDir = File(paths.maiBotDir, "bots/$botId")
        writeFile(File(botDir, "config/bot_config.toml"), "bot")
        writeFile(File(botDir, "config/builtin_plugins/qq_official_adapter/config.toml"), "qq")
        writeFile(File(botDir, "config/old/bot_config.toml.bak"), "old")
        writeFile(File(botDir, "data/MaiBot.db"), "db")
        writeFile(File(botDir, "data/MaiBot.db-wal"), "wal")
        writeFile(File(botDir, "data/a-memorix/graph.db"), "graph")
        writeFile(File(botDir, "data/a-memorix/.a_memorix_runtime_writer.lock"), "lock")
        writeFile(File(botDir, "data/prompt_imgs/preview.png"), "img")
        writeFile(File(botDir, "plugins/sample/config.toml"), "plugin")
        writeFile(File(botDir, "plugins/sample/.git/HEAD"), "git")
        writeFile(File(botDir, "plugins/sample/__pycache__/mod.pyc"), "pyc")

        // SnowLuma + NapCat + 共用 token
        writeFile(File(paths.rootfsDir, "root/snowluma/data/config/onebot.json"), "sl")
        writeFile(File(paths.rootfsDir, "root/.maidroid/snowluma_webui_password"), "pw")
        writeFile(File(paths.rootfsDir, "root/napcat/config/onebot11.json"), "nc")
        writeFile(File(paths.rootfsDir, "root/.maidroid/qq_client"), "snowluma")
        writeFile(File(paths.rootfsDir, "root/.maidroid/napcat_token"), "tok")

        // 符号链接应当被跳过
        val linkTarget = File(botDir, "config/bot_config.toml")
        val linkPath = File(botDir, "config/linked.toml").toPath()
        runCatching { Files.createSymbolicLink(linkPath, linkTarget.toPath()) }

        val planner = BackupArchivePlanner(paths) {
            listOf(MaiBotInstance(dir = botDir, containerDir = "/opt/MaiBot/bots/$botId", botId = botId))
        }

        val entries = planner.plan(BackupComponent.entries.toSet())
        val archivePaths = entries.map { it.archivePath }

        assertTrue("maibot/host/registry.sqlite3" in archivePaths)
        assertTrue("maibot/host/model_catalog.toml" in archivePaths)
        assertTrue("maibot/bots/$botId/config/bot_config.toml" in archivePaths)
        assertTrue("maibot/bots/$botId/config/builtin_plugins/qq_official_adapter/config.toml" in archivePaths)
        assertTrue("maibot/bots/$botId/data/MaiBot.db" in archivePaths)
        assertTrue("maibot/bots/$botId/data/a-memorix/graph.db" in archivePaths)
        assertTrue("maibot/bots/$botId/plugins/sample/config.toml" in archivePaths)
        assertTrue("snowluma/config/onebot.json" in archivePaths)
        assertTrue("snowluma/meta/snowluma_webui_password" in archivePaths)
        assertTrue("napcat/config/onebot11.json" in archivePaths)

        // 过滤项校验
        assertFalse(archivePaths.any { it.contains("config/old/") })
        assertFalse(archivePaths.any { it.contains("prompt_imgs/") })
        assertFalse(archivePaths.any { it.endsWith(".lock") })
        assertFalse(archivePaths.any { it.endsWith("-wal") })
        assertFalse(archivePaths.any { it.contains(".git") || it.contains("__pycache__") || it.endsWith(".pyc") })
        assertFalse(archivePaths.any { it.endsWith("linked.toml") })

        // SnowLuma 与 NapCat 的共用文件只保留一份
        assertEquals(1, archivePaths.count { it == "shared/napcat_token" })
        assertEquals(1, archivePaths.count { it == "shared/qq_client" })
    }

    @Test
    fun `planner keeps registry when only memory component is selected`() {
        val filesDir = tempFolder.newFolder("files_mem")
        val libsDir = tempFolder.newFolder("libs_mem")
        val paths = RuntimePaths(appFilesDir = filesDir, nativeLibraryDir = libsDir)

        writeFile(File(paths.maiBotDir, "host/registry.sqlite3"), "reg")
        writeFile(File(paths.maiBotDir, "host/model_catalog.toml"), "models")
        writeFile(File(paths.maiBotDir, "config/bot_config.toml"), "cfg")
        writeFile(File(paths.maiBotDir, "data/MaiBot.db"), "db")

        val planner = BackupArchivePlanner(paths) {
            listOf(MaiBotInstance(dir = paths.maiBotDir, containerDir = "/opt/MaiBot", botId = null))
        }
        val archivePaths = planner.plan(setOf(BackupComponent.MaiBotMemory)).map { it.archivePath }

        assertTrue("maibot/host/registry.sqlite3" in archivePaths)
        assertTrue("maibot/root/data/MaiBot.db" in archivePaths)
        assertFalse("maibot/host/model_catalog.toml" in archivePaths)
        assertFalse("maibot/root/config/bot_config.toml" in archivePaths)
    }

    @Test
    fun `detectAvailableComponents hides uninstalled protocol clients`() {
        val filesDir = tempFolder.newFolder("files_avail")
        val libsDir = tempFolder.newFolder("libs_avail")
        val paths = RuntimePaths(appFilesDir = filesDir, nativeLibraryDir = libsDir)
        val planner = BackupArchivePlanner(paths)

        assertEquals(
            setOf(BackupComponent.MaiBotConfig, BackupComponent.MaiBotMemory, BackupComponent.Plugins),
            planner.detectAvailableComponents()
        )

        // 仅安装 SnowLuma
        writeFile(File(paths.rootfsDir, "root/.maidroid/snowluma_ready"), "1.0.0")
        writeFile(File(paths.rootfsDir, "root/snowluma/app/index.mjs"), "")
        writeFile(File(paths.rootfsDir, "root/snowluma/data/config/onebot.json"), "{}")
        assertEquals(
            setOf(
                BackupComponent.MaiBotConfig,
                BackupComponent.MaiBotMemory,
                BackupComponent.Plugins,
                BackupComponent.SnowLuma
            ),
            planner.detectAvailableComponents()
        )

        // 再补装 NapCat
        writeFile(File(paths.rootfsDir, "root/.maidroid/napcat_ready"), "1")
        writeFile(File(paths.rootfsDir, "root/napcat/config/onebot11.json"), "{}")
        assertEquals(BackupComponent.entries.toSet(), planner.detectAvailableComponents())

        // 仅留 NapCat 时不返回 SnowLuma
        File(paths.rootfsDir, "root/.maidroid/snowluma_ready").delete()
        assertEquals(
            setOf(
                BackupComponent.MaiBotConfig,
                BackupComponent.MaiBotMemory,
                BackupComponent.Plugins,
                BackupComponent.NapCat
            ),
            planner.detectAvailableComponents()
        )
    }

    private fun writeFile(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}
