package org.maiwithu.maidroid.platform.qqofficial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.maiwithu.maidroid.maibot.MaiBotInstance
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import java.io.File

class QqOfficialPlatformTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `missing config reads as not configured`() {
        assertEquals(QqOfficialCredentials(), QqOfficialAdapterConfig.read(null))
        assertFalse(QqOfficialAdapterConfig.read("").configured)
    }

    @Test
    fun `first write creates a minimal enabled config`() {
        val text = QqOfficialAdapterConfig.write(null, "102000001", "secret")
        assertEquals(
            """
            [plugin]
            enabled = true
            config_version = "0.4.1"

            [qq_official]
            app_id = "102000001"
            app_secret = "secret"

            """.trimIndent(),
            text
        )
        assertEquals(QqOfficialCredentials(true, "102000001", "secret"), QqOfficialAdapterConfig.read(text))
    }

    @Test
    fun `rewrite keeps other sections and only replaces the three managed keys`() {
        val existing = """
            # 由 MaiBot 生成
            [plugin]
            enabled = false
            config_version = "0.4.1"

            [qq_official]
            app_id = "old"
            app_secret = "old-secret"
            api_base_url = "https://api.bot.qq.com"
            unified_account_id = "12345"

            [mute]
            enabled = true
            allowed_groups = ["1", "2"]
        """.trimIndent() + "\n"

        val text = QqOfficialAdapterConfig.write(existing, "new", "new-secret")

        assertEquals(
            existing
                .replace("enabled = false", "enabled = true")
                .replace("app_id = \"old\"", "app_id = \"new\"")
                .replace("app_secret = \"old-secret\"", "app_secret = \"new-secret\""),
            text
        )
        // [mute] 节里的 enabled 不能被当成插件开关改掉。
        assertTrue(text.contains("[mute]\nenabled = true\nallowed_groups = [\"1\", \"2\"]"))
    }

    @Test
    fun `missing keys go to the end of their section and missing sections are appended`() {
        val existing = """
            [plugin]
            config_version = "0.4.1"

            [mute]
            enabled = false
        """.trimIndent()

        val text = QqOfficialAdapterConfig.write(existing, "1", "2")

        assertEquals(
            """
            [plugin]
            config_version = "0.4.1"
            enabled = true

            [mute]
            enabled = false

            [qq_official]
            app_id = "1"
            app_secret = "2"

            """.trimIndent(),
            text
        )
    }

    @Test
    fun `secrets with quotes and backslashes survive a round trip`() {
        val secret = "a\"b\\c\td"
        val text = QqOfficialAdapterConfig.write(null, "1", secret)
        assertTrue(text.contains("app_secret = \"a\\\"b\\\\c\\td\""))
        assertEquals(secret, QqOfficialAdapterConfig.read(text).appSecret)
    }

    @Test
    fun `literal strings and trailing comments are understood`() {
        val text = """
            [plugin]
            enabled = true # 开关
            [qq_official]
            app_id = '1020'
            app_secret = "xA"
        """.trimIndent()
        assertEquals(QqOfficialCredentials(true, "1020", "xA"), QqOfficialAdapterConfig.read(text))
    }

    @Test
    fun `connection errors are recognised from adapter log lines`() {
        assertEquals(
            "未填写 AppID 或 AppSecret",
            qqOfficialConnectionError("[plugin] ERROR QQ 官方适配器缺少凭据，请在插件配置 [qq_official] 中填写 app_id 和 app_secret")
        )
        assertEquals(
            "获取访问凭证失败，请检查 AppID 和 AppSecret（HTTP 401, code=100016）",
            qqOfficialConnectionError(
                "WARNING QQ 官方网关连接失败，将重试: 获取 QQ 访问凭证失败: HTTP 401, code=100016"
            )
        )
        assertEquals(
            "当前网络的 IP 不在机器人的 IP 白名单内，请在 QQ 开放平台清空 IP 白名单",
            qqOfficialConnectionError("QQ 官方网关连接失败，将重试: 获取 QQ 访问凭证失败: HTTP 200, code=11298")
        )
        assertEquals(
            "连接 QQ 开放平台失败：Cannot connect to host api.bot.qq.com:443",
            qqOfficialConnectionError("QQ 官方网关连接失败，将重试: Cannot connect to host api.bot.qq.com:443")
        )
    }

    @Test
    fun `self healing hints and unrelated lines are not errors`() {
        assertNull(qqOfficialConnectionError("QQ 网关心跳未确认，重新建立连接"))
        assertNull(qqOfficialConnectionError("[NapCat] 连接失败"))
        assertTrue(isMaiBotStartLine("${MaiBotProcessSupervisor.PROCESS_STARTED_LOG} (PID: 123)"))
        assertFalse(isMaiBotStartLine("[MaiBot] Starting: proot"))
    }

    @Test
    fun `status text follows install, credentials, runtime and errors`() {
        val ready = QqOfficialPlatformState(
            phase = QqOfficialInstallPhase.Installed,
            installed = true,
            credentials = QqOfficialCredentials(true, "1", "2"),
            maiBotRunning = true
        )
        assertEquals("已启用", qqOfficialStatusText(ready))
        assertTrue(ready.presumedOnline)
        assertEquals("连接失败", qqOfficialStatusText(ready.copy(connectionError = "x")))
        assertFalse(ready.copy(connectionError = "x").presumedOnline)
        assertEquals("等待 MaiBot 启动", qqOfficialStatusText(ready.copy(maiBotRunning = false)))
        assertEquals("插件已停用", qqOfficialStatusText(ready.copy(credentials = QqOfficialCredentials(false, "1", "2"))))
        assertEquals("待填写 AppID", qqOfficialStatusText(ready.copy(credentials = QqOfficialCredentials(true, "", "2"))))
        assertEquals("未安装", qqOfficialStatusText(QqOfficialPlatformState()))
        assertEquals(
            "正在安装",
            qqOfficialStatusText(QqOfficialPlatformState(phase = QqOfficialInstallPhase.Installing))
        )
    }

    @Test
    fun `install script supports multi-instance plugins directory`() {
        val instancePlugins = "/opt/MaiBot/bots/0123456789abcdef0123456789abcdef/plugins"
        val body = QqOfficialAdapterScripts.install(pluginsDir = instancePlugins).body
        assertTrue(body.contains("mkdir -p '$instancePlugins'"))
        assertTrue(body.contains("'$instancePlugins/.MaiBot-QQ-Adapter.tmp'"))
        assertTrue(body.contains("'$instancePlugins/MaiBot-QQ-Adapter'"))
    }

    @Test
    fun `built-in detection and activation semantics follow 1_4_8 layout`() {
        val maiBotDir = folder.newFolder("MaiBot")
        assertFalse(isQqOfficialBuiltIn(maiBotDir))

        val manifest = File(maiBotDir, "src/plugins/built_in/qq_official_adapter/_manifest.json")
        manifest.parentFile?.mkdirs()
        manifest.writeText("""{"id": "Mai-with-u.qq-adapter", "version": "0.5.0"}""")
        assertTrue(isQqOfficialBuiltIn(maiBotDir))

        val botId = "917b382e17164deeaf1a14caa3a5e555"
        val instanceDir = File(maiBotDir, "bots/$botId")
        val instance = MaiBotInstance(instanceDir, "/opt/MaiBot/bots/$botId", botId, "Main", true, true)
        val configFile = builtInQqOfficialConfigFile(instance)
        assertEquals(File(instanceDir, "config/builtin_plugins/qq_official_adapter/config.toml"), configFile)
        assertFalse(isBuiltInQqOfficialActivated(instance))

        // 默认生成的停用且空凭据配置不应算作用户已接入
        configFile.parentFile?.mkdirs()
        configFile.writeText(
            """
            [plugin]
            enabled = false
            config_version = "0.5.4"

            [qq_official]
            app_id = ""
            app_secret = ""
            """.trimIndent()
        )
        assertFalse(isBuiltInQqOfficialActivated(instance))

        // 仅开启开关视为已激活
        configFile.writeText(configFile.readText().replace("enabled = false", "enabled = true"))
        assertTrue(isBuiltInQqOfficialActivated(instance))

        // 填写过 app_id 或 app_secret（即便用户后来临时停用）也留在已安装列表中，方便进配置弹窗修改或重开
        configFile.writeText(
            """
            [plugin]
            enabled = false
            config_version = "0.5.4"

            [qq_official]
            app_id = "102000001"
            app_secret = ""
            """.trimIndent()
        )
        assertTrue(isBuiltInQqOfficialActivated(instance))
    }

    @Test
    fun `built-in target resolution prioritizes activated instance before running default`() {
        val maiBotDir = folder.newFolder("MaiBotTargets")
        val idA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val idB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val dirA = File(maiBotDir, "bots/$idA").apply { mkdirs() }
        val dirB = File(maiBotDir, "bots/$idB").apply { mkdirs() }
        val instanceA = MaiBotInstance(dirA, "/opt/MaiBot/bots/$idA", idA, "A", initialized = true, desiredRunning = false)
        val instanceB = MaiBotInstance(dirB, "/opt/MaiBot/bots/$idB", idB, "B", initialized = true, desiredRunning = true)
        val fallback = MaiBotInstance(maiBotDir, "/opt/MaiBot")

        // 都未激活时优先选中正在运行且已初始化的 B
        assertEquals(dirB, resolveBuiltInQqOfficialTarget(listOf(instanceA, instanceB), fallback).dir)

        // 若实例 A 已激活内置适配器，即使它当前未处于自动运行状态也继续命中 A
        val configA = builtInQqOfficialConfigFile(instanceA)
        configA.parentFile?.mkdirs()
        configA.writeText(
            """
            [plugin]
            enabled = true
            config_version = "0.5.4"

            [qq_official]
            app_id = "102000001"
            app_secret = "secret"
            """.trimIndent()
        )
        assertEquals(dirA, resolveBuiltInQqOfficialTarget(listOf(instanceA, instanceB), fallback).dir)
    }

    @Test
    fun `built-in config write preserves 0_5_4 version and uses built-in default when blank`() {
        val freshBuiltIn = QqOfficialAdapterConfig.write(
            existing = null,
            appId = "102000001",
            appSecret = "secret",
            defaultConfigVersion = QqOfficialAdapterConfig.FallbackBuiltInConfigVersion
        )
        assertTrue(freshBuiltIn.contains("""config_version = "0.5.4""""))
        assertEquals(QqOfficialCredentials(true, "102000001", "secret"), QqOfficialAdapterConfig.read(freshBuiltIn))

        val existing = """
            [plugin]
            enabled = false
            config_version = "0.5.4"

            [qq_official]
            app_id = ""
            app_secret = ""
            api_base_url = "https://api.bot.qq.com"
            enable_mapping = false

            [mute]
            enabled = true

            [advanced]
            reconnect_delay_sec = 5.0
        """.trimIndent() + "\n"
        val updated = QqOfficialAdapterConfig.write(
            existing = existing,
            appId = "102000001",
            appSecret = "testsecret1234",
            defaultConfigVersion = QqOfficialAdapterConfig.FallbackBuiltInConfigVersion
        )
        assertTrue(updated.contains("enabled = true"))
        assertTrue(updated.contains("""config_version = "0.5.4""""))
        assertTrue(updated.contains("""app_id = "102000001""""))
        assertTrue(updated.contains("""app_secret = "testsecret1234""""))
        assertTrue(updated.contains("[advanced]\nreconnect_delay_sec = 5.0"))
        assertTrue(updated.contains("[mute]\nenabled = true"))
    }

    @Test
    fun `settings parser extracts dynamic default config_version and ignores comments`() {
        val samplePy = """
            # config_version = "9.9.9"
            class PluginConfig(ConfigBase):
                config_version: str = Field(default="0.6.2", description="配置版本")
        """.trimIndent()
        assertEquals("0.6.2", QqOfficialAdapterConfig.parseDefaultConfigVersion(samplePy))

        val plainAssign = """
            config_version = '0.7.0'
        """.trimIndent()
        assertEquals("0.7.0", QqOfficialAdapterConfig.parseDefaultConfigVersion(plainAssign))

        assertNull(QqOfficialAdapterConfig.parseDefaultConfigVersion("# config_version = \"1.0.0\"\n"))
        assertNull(QqOfficialAdapterConfig.parseDefaultConfigVersion(null))

        val adapterDir = folder.newFolder("qq_official_adapter")
        File(adapterDir, "settings.py").writeText(samplePy)
        assertEquals(
            "0.6.2",
            readQqOfficialDefaultConfigVersion(adapterDir, QqOfficialAdapterConfig.FallbackBuiltInConfigVersion)
        )
        assertEquals(
            QqOfficialAdapterConfig.FallbackBuiltInConfigVersion,
            readQqOfficialDefaultConfigVersion(
                File(adapterDir, "missing"),
                QqOfficialAdapterConfig.FallbackBuiltInConfigVersion
            )
        )
    }

    @Test
    fun `http request error lines from built-in adapter are recognized`() {
        assertEquals(
            "获取访问凭证失败，请检查 AppID 和 AppSecret（HTTP 400, code=10004）",
            qqOfficialConnectionError(
                "WARNING QQ 官方网关 HTTP 请求失败，将重试: 获取 QQ 访问凭证失败: HTTP 400, code=10004"
            )
        )
    }
}
