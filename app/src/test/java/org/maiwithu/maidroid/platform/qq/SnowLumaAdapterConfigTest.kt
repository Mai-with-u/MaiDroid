package org.maiwithu.maidroid.platform.qq

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.maibot.PluginHostVersionRange
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SnowLumaAdapterConfigTest {
    // MaiBot 按新适配器默认值生成的配置（节选）：默认关着、连 3001、没有 token
    private val generated = """
        [plugin]
        enabled = false
        qq_face_parse_mode = "description"
        config_version = "2.7.1"

        [chat_abilities]
        enable_private_chat_tool = false
        enable_poke_tool = false

        [client]
        client_type = "auto"
        server = "127.0.0.1"
        port = 3001
        token = ""
        heartbeat_interval = 30.0

        [notice]
        enabled = true

        [filters]
        ban_user_id = []
        ban_qq_bot = false
        regex_filter_enabled = false
        regex_filter_mode = "blacklist"
        regex_filter_patterns = []

        [debug]
        ignore_self_message = true
    """.trimIndent() + "\n"

    // 旧适配器 MaiBot-Napcat-Adapter 的配置：用户改过几项
    private val legacy = """
        [plugin]
        enabled = true
        enable_private_chat_tool = true
        config_version = "0.1.0"

        [napcat_server]
        host = "127.0.0.1"
        port = 8095
        token = "tok"

        [chat]
        group_list_type = "whitelist"
        group_list = ["123"]
        ban_user_id = [10001, 10002] # 广告号
        ban_qq_bot = true

        [filters]
        ignore_self_message = false
        regex_filter_enabled = true
        regex_filter_patterns = [
            "^#",
        ]

        [notice]
        enabled = false
    """.trimIndent() + "\n"

    @Test
    fun `only configs written by MaiBot count as generated`() {
        assertTrue(SnowLumaAdapterConfig.isGenerated(generated))
        assertFalse(SnowLumaAdapterConfig.isGenerated("[plugin]\nenabled = true\n\n[client]\nport = 8095\n"))
        assertFalse(SnowLumaAdapterConfig.isGenerated(""))
    }

    @Test
    fun `migration enables the adapter and points it at NapCat`() {
        val migrated = SnowLumaAdapterConfig.migrate(generated, "tok", legacy = null)

        assertEquals(
            generated
                .replace("enabled = false\nqq_face", "enabled = true\nqq_face")
                .replace("port = 3001", "port = 8095")
                .replace("token = \"\"", "token = \"tok\""),
            migrated
        )
        assertTrue(SnowLumaAdapterConfig.isMigrated(migrated, "tok"))
        assertFalse(SnowLumaAdapterConfig.isMigrated(generated, "tok"))
        assertFalse(SnowLumaAdapterConfig.isMigrated(migrated, "other"))
    }

    @Test
    fun `legacy settings with a counterpart are carried over`() {
        val migrated = SnowLumaAdapterConfig.migrate(generated, "tok", legacy)

        assertTrue(migrated.contains("[chat_abilities]\nenable_private_chat_tool = true\n"))
        assertTrue(migrated.contains("ban_user_id = [10001, 10002] # 广告号\nban_qq_bot = true\n"))
        assertTrue(migrated.contains("regex_filter_enabled = true\n"))
        assertTrue(migrated.contains("[notice]\nenabled = false\n"))
        assertTrue(migrated.contains("[debug]\nignore_self_message = false\n"))
        // 旧的群聊名单新适配器没有对应项，连接项以 MaiDroid 写的为准，版本号保留 MaiBot 生成的
        assertFalse(migrated.contains("group_list"))
        assertFalse(migrated.contains("napcat_server"))
        assertTrue(migrated.contains("config_version = \"2.7.1\""))
    }

    @Test
    fun `multi-line legacy values are skipped instead of breaking the file`() {
        val migrated = SnowLumaAdapterConfig.migrate(generated, "tok", legacy)

        assertTrue(migrated.contains("regex_filter_patterns = []\n"))
        assertFalse(migrated.contains("\"^#\""))
    }

    @Test
    fun `migrating an already migrated config changes nothing`() {
        val migrated = SnowLumaAdapterConfig.migrate(generated, "tok", legacy)

        assertEquals(migrated, SnowLumaAdapterConfig.migrate(migrated, "tok", legacy = null))
    }

    @Test
    fun `MaiBot older or newer than the adapter manifest range is rejected`() {
        val range = PluginHostVersionRange(minVersion = "1.4.4", maxVersion = "1.4.99")
        val tooOld = adapterHostVersionProblem("1.4.3", range)

        assertTrue(tooOld!!.contains("1.4.4"))
        assertTrue(tooOld.contains("当前 1.4.3"))
        assertNull(adapterHostVersionProblem("1.4.4", range))
        assertNull(adapterHostVersionProblem("1.4.8", range))
        assertNull(adapterHostVersionProblem("1.4.99", range))
        assertTrue(adapterHostVersionProblem("1.5.0", range)!!.contains("最高支持 MaiBot 1.4.99"))
    }

    @Test
    fun `unknown MaiBot version or missing range does not block the install`() {
        val range = PluginHostVersionRange(minVersion = "1.4.4", maxVersion = "1.4.99")
        assertNull(adapterHostVersionProblem(null, range))
        assertNull(adapterHostVersionProblem("dev", range))
        assertNull(adapterHostVersionProblem("1.4.3", null))
    }

    @Test
    fun `cloneToStaging downloads adapter into staging without removing legacy adapter`() {
        val script = SnowLumaAdapterScripts.cloneToStaging().body

        assertTrue(script.contains("maidroid_github_clone 'https://github.com/Mai-with-u/MaiBot-SnowLuma-Adapter.git'"))
        assertTrue(script.contains("'/opt/MaiBot/plugins/.MaiBot-SnowLuma-Adapter.tmp'"))
        // 暂存脚本只负责克隆，不直接删正式目录或旧适配器，需先等宿主校验 manifest 兼容再清理
        assertFalse(script.contains("maibot-team\\.napcat-adapter"))
        assertFalse(script.contains("mv "))
        assertTrue(SnowLumaAdapterScripts.cloneToStaging(logPrefix = "[SnowLuma]").body.contains("'[SnowLuma]'"))
        assertFalse(SnowLumaAdapterScripts.cloneToStaging(logPrefix = "[SnowLuma]").body.contains("[NapCat]"))

        val instancePlugins = "/opt/MaiBot/bots/0123456789abcdef0123456789abcdef/plugins"
        val instanceScript = SnowLumaAdapterScripts.cloneToStaging("[SnowLuma]", pluginsDir = instancePlugins).body
        assertTrue(instanceScript.contains("mkdir -p '$instancePlugins'"))
        assertTrue(instanceScript.contains("'$instancePlugins/.MaiBot-SnowLuma-Adapter.tmp'"))
    }

    @Test
    fun `diagnoseAdapterConfigWaitFailure identifies version mismatch, dependency block, and generic timeout`() {
        val versionRejected = diagnoseAdapterConfigWaitFailure(
            listOf(
                "[MaiBot] 插件 maibot-team.snowluma-adapter manifest 校验失败: Host 版本不兼容: 版本 1.4.3 低于最小要求 1.4.4"
            )
        )
        assertTrue(versionRejected.contains("低于最小要求"))

        val depBlocked = diagnoseAdapterConfigWaitFailure(
            listOf(
                "[MaiBot] 插件 maibot-team.snowluma-adapter 已被 Host 依赖流水线阻止加载: silk-python 安装失败"
            )
        )
        assertTrue(depBlocked.contains("silk-python"))

        val hostDepPrepareFailed = diagnoseAdapterConfigWaitFailure(
            listOf("插件依赖准备失败: 安装依赖超时")
        )
        assertTrue(hostDepPrepareFailed.contains("silk-python"))

        val hostDepResolveFailed = diagnoseAdapterConfigWaitFailure(
            listOf("依赖联合解析失败，Bot 未停止、环境未修改：No solution found")
        )
        assertTrue(hostDepResolveFailed.contains("silk-python"))

        val stillPreparingDeps = diagnoseAdapterConfigWaitFailure(
            listOf(
                "[MaiBot] Process started (PID: 17976)",
                "MaiBot 宿主已启动：/opt/MaiBot",
                "正在检查并准备插件依赖，完成后启动 Bot…"
            )
        )
        assertTrue(stillPreparingDeps.contains("仍在下载安装 QQ 适配器依赖"))

        val preparedThenStalled = diagnoseAdapterConfigWaitFailure(
            listOf(
                "正在检查并准备插件依赖，完成后启动 Bot…",
                "插件依赖准备完成"
            )
        )
        assertTrue(preparedThenStalled.contains("等了 5 分钟"))

        val exitedAfterPrepare = diagnoseAdapterConfigWaitFailure(
            listOf(
                "正在检查并准备插件依赖，完成后启动 Bot…",
                "[MaiBot] Process exited with code: 130"
            )
        )
        assertTrue(exitedAfterPrepare.contains("等了 5 分钟"))

        val generic = diagnoseAdapterConfigWaitFailure(
            listOf("[MaiBot] ValueError: 图片嵌入必须配置 Provider 对应的 image_embedding_input 或 image_embedding_body")
        )
        assertTrue(generic.contains("等了 5 分钟"))
    }

    @Test
    fun `onebot token is created once and reused by both qq clients`() {
        val script = QqOneBot.ensureTokenScript("fresh", variable = "napcat_token")

        // 已经存过就沿用，换协议端、重装都不换 token，适配器配置才对得上
        assertTrue(script.contains("if [ -s /root/.maidroid/napcat_token ]; then"))
        assertTrue(script.contains("napcat_token=\"${'$'}(cat /root/.maidroid/napcat_token)\""))
        assertTrue(script.contains("napcat_token=\"fresh\""))
    }

    @Test
    fun `concurrent adapter installs are serialized and second caller skips cloning`() = runBlocking {
        val mutex = Mutex()
        val installed = AtomicBoolean(false)
        val activeClones = AtomicInteger(0)
        val maxConcurrentClones = AtomicInteger(0)
        val totalCloneCalls = AtomicInteger(0)
        val logs = mutableListOf<String>()

        suspend fun runOnce(client: QqClient): String? = executeSerializedInstall(
            mutex = mutex,
            client = client,
            resolveTarget = { "/opt/MaiBot/bots/abc/plugins" },
            containerPluginsDir = { it },
            readLegacyConfig = {},
            isInstalled = { installed.get() },
            runClone = {
                val active = activeClones.incrementAndGet()
                maxConcurrentClones.updateAndGet { prev -> maxOf(prev, active) }
                totalCloneCalls.incrementAndGet()
                delay(60)
                activeClones.decrementAndGet()
                ContainerCommandResult(exitCode = 0, output = "ok")
            },
            cleanupStaging = {},
            stageAndPromote = {
                installed.set(true)
                null
            },
            checkInstalledProblem = { null },
            removeLegacyAdapters = { emptyList() },
            removeOrphanRoot = { null },
            onLine = { line -> synchronized(logs) { logs.add(line) } },
            ioDispatcher = Dispatchers.Default
        )

        val (first, second) = coroutineScope {
            val a = async(Dispatchers.Default) { runOnce(QqClient.NapCat) }
            val b = async(Dispatchers.Default) { runOnce(QqClient.SnowLuma) }
            a.await() to b.await()
        }

        assertNull(first)
        assertNull(second)
        assertEquals(1, maxConcurrentClones.get())
        assertEquals(1, totalCloneCalls.get())
        assertTrue(logs.any { it.contains("QQ 适配器已存在，跳过下载") })
    }
}
