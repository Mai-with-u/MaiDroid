package org.maiwithu.maidroid.maibot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MaiBotInstancesTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun setupWorkspace(): Pair<File, File> {
        val rootfs = folder.newFolder("debian")
        val maiBotDir = File(rootfs, "opt/MaiBot").apply { mkdirs() }
        return rootfs to maiBotDir
    }

    private fun createRegistryStub(maiBotDir: File) {
        File(maiBotDir, "host/registry.sqlite3").apply {
            parentFile?.mkdirs()
            writeText("sqlite stub")
        }
    }

    private fun installPluginStub(instanceDir: File, pluginId: String, dirName: String) {
        File(instanceDir, "plugins/$dirName").apply {
            mkdirs()
            File(this, "_manifest.json").writeText("""{"manifest_version": 2, "id": "$pluginId"}""")
            File(this, "plugin.py").writeText("")
        }
    }

    @Test
    fun `registry entry with dot resolves to root directory for adopted upgrades`() {
        val (rootfs, maiBotDir) = setupWorkspace()
        createRegistryStub(maiBotDir)

        val instances = discoverMaiBotInstances(maiBotDir, rootfs) {
            listOf(MaiBotRegistryRow("bot0", "MainBot", ".", "initialized", "running"))
        }

        assertEquals(1, instances.size)
        assertEquals(maiBotDir, instances[0].dir)
        assertEquals("/opt/MaiBot", instances[0].containerDir)
        assertEquals("/opt/MaiBot/plugins", instances[0].containerPluginsDir)
        assertTrue(instances[0].initialized)
        assertTrue(instances[0].desiredRunning)
    }

    @Test
    fun `registry entry with bots subdir resolves to isolated instance directory`() {
        val (rootfs, maiBotDir) = setupWorkspace()
        createRegistryStub(maiBotDir)
        val botId = "0123456789abcdef0123456789abcdef"

        val instances = discoverMaiBotInstances(maiBotDir, rootfs) {
            listOf(MaiBotRegistryRow(botId, "FreshBot", "bots/$botId", "initialized", "running"))
        }

        assertEquals(1, instances.size)
        assertEquals(File(maiBotDir, "bots/$botId"), instances[0].dir)
        assertEquals("/opt/MaiBot/bots/$botId", instances[0].containerDir)
        assertEquals("/opt/MaiBot/bots/$botId/plugins", instances[0].containerPluginsDir)
        assertEquals(File(maiBotDir, "bots/$botId/temp/worker-console.log"), instances[0].workerConsoleLog)
    }

    @Test
    fun `resolvePluginTargetInstance prioritizes existing plugin then initialized running instance`() {
        val (rootfs, maiBotDir) = setupWorkspace()
        val idA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val idB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val idC = "cccccccccccccccccccccccccccccccc"
        val dirA = File(maiBotDir, "bots/$idA").apply { mkdirs() }
        val dirB = File(maiBotDir, "bots/$idB").apply { mkdirs() }
        val dirC = File(maiBotDir, "bots/$idC").apply { mkdirs() }

        val instances = listOf(
            MaiBotInstance(dirA, "/opt/MaiBot/bots/$idA", idA, "A", initialized = false, desiredRunning = true),
            MaiBotInstance(dirB, "/opt/MaiBot/bots/$idB", idB, "B", initialized = true, desiredRunning = false),
            MaiBotInstance(dirC, "/opt/MaiBot/bots/$idC", idC, "C", initialized = true, desiredRunning = true)
        )
        val fallback = MaiBotInstance(maiBotDir, "/opt/MaiBot")
        val pluginId = "maibot-team.snowluma-adapter"

        // 还没在任何实例装插件时，优先选 initialized && desiredRunning 的实例 C
        assertEquals(dirC, resolvePluginTargetInstance(instances, pluginId, "MaiBot-SnowLuma-Adapter", fallback).dir)

        // 把 desiredRunning 的 C 排除后，在两个未运行的实例里优先选 initialized 的 B
        val noRunning = instances.take(2)
        assertEquals(dirB, resolvePluginTargetInstance(noRunning, pluginId, "MaiBot-SnowLuma-Adapter", fallback).dir)

        // 若旧实例 B 已经装过该插件，则继续命中已有插件的实例 B（即使未设为自动启动）
        installPluginStub(dirB, pluginId, "MaiBot-SnowLuma-Adapter")
        assertEquals(dirB, resolvePluginTargetInstance(instances, pluginId, "MaiBot-SnowLuma-Adapter", fallback).dir)
    }

    @Test
    fun `falls back to scanning bots directory when registry is unreadable or missing without root config`() {
        val (rootfs, maiBotDir) = setupWorkspace()
        createRegistryStub(maiBotDir)
        val id1 = "11111111111111111111111111111111"
        val id2 = "22222222222222222222222222222222"
        File(maiBotDir, "bots/$id2/config").mkdirs()
        File(maiBotDir, "bots/$id2/config/bot_config.toml").writeText("[bot]\n")
        File(maiBotDir, "bots/$id1").mkdirs()
        File(maiBotDir, "bots/not-a-bot-dir").mkdirs()

        // 注册表读失败时走 bots/ 目录扫描，并优先包含 config/bot_config.toml 的目录
        val fromFailedRegistry = discoverMaiBotInstances(maiBotDir, rootfs) { null }
        assertEquals(listOf(File(maiBotDir, "bots/$id2")), fromFailedRegistry.map { it.dir })

        // 删除注册表后仍无根 bot_config.toml 时照样识别 bots/
        File(maiBotDir, "host/registry.sqlite3").delete()
        val withoutRegistry = discoverMaiBotInstances(maiBotDir, rootfs) { null }
        assertEquals(listOf(File(maiBotDir, "bots/$id2")), withoutRegistry.map { it.dir })
    }

    @Test
    fun `legacy single-instance layout falls back to MaiBot root when root config exists without registry`() {
        val (rootfs, maiBotDir) = setupWorkspace()
        File(maiBotDir, "config").mkdirs()
        File(maiBotDir, "config/bot_config.toml").writeText("[bot]\n")

        val instances = discoverMaiBotInstances(maiBotDir, rootfs) { null }

        assertEquals(1, instances.size)
        assertEquals(maiBotDir, instances[0].dir)
        assertEquals("/opt/MaiBot", instances[0].containerDir)
    }

    @Test
    fun `path traversal segments escaping bounds are rejected safely`() {
        val (rootfs, maiBotDir) = setupWorkspace()
        createRegistryStub(maiBotDir)
        val validId = "abcdef0123456789abcdef0123456789"

        val instances = discoverMaiBotInstances(maiBotDir, rootfs) {
            listOf(
                MaiBotRegistryRow("bad1", "EscRel", "../../etc", "initialized", "running"),
                MaiBotRegistryRow("bad2", "EscAbs", "/../etc/passwd", "initialized", "running"),
                MaiBotRegistryRow("bad3", "EscMid", "bots/../../etc", "initialized", "running"),
                MaiBotRegistryRow(validId, "GoodBot", "bots/./$validId", "initialized", "running")
            )
        }

        assertEquals(1, instances.size)
        assertEquals(validId, instances[0].botId)
        assertEquals("/opt/MaiBot/bots/$validId", instances[0].containerDir)
    }
}
