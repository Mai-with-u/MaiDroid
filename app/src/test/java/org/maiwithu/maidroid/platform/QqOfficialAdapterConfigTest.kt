package org.maiwithu.maidroid.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.maibot.MaiBotSupervisor

class QqOfficialAdapterConfigTest {

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
                "\u001B[33mWARNING\u001B[0m QQ 官方网关连接失败，将重试: 获取 QQ 访问凭证失败: HTTP 401, code=100016"
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
        assertTrue(isMaiBotStartLine("${MaiBotSupervisor.PROCESS_STARTED_LOG} (PID: 123)"))
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
}
