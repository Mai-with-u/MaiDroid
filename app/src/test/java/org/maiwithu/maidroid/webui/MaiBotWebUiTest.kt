package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class MaiBotWebUiTest {
    @Test
    fun extractAccessTokenFromLogsFindsHostTemporaryPassword() {
        // 多实例宿主版的 MaiBot 只打印“WebUI 临时登录密码：…”（全角冒号）
        val token = MaiBotWebUi.extractAccessTokenFromLogs(listOf("WebUI 临时登录密码：hostTemp123"))

        assertEquals("hostTemp123", token)
    }

    @Test
    fun readAccessTokenPrefersHostCredentialsOverLegacyFile() {
        // 升级后旧的 data/webui.json 可能还留着过期令牌，新位置 host/webui.json 要优先
        val dir = Files.createTempDirectory("webui").toFile()
        try {
            val host = File(dir, "host/webui.json").apply { parentFile?.mkdirs(); writeText("""{"access_token":"hostToken"}""") }
            val legacy = File(dir, "data/webui.json").apply { parentFile?.mkdirs(); writeText("""{"access_token":"oldToken"}""") }

            assertEquals("hostToken", MaiBotWebUi.readAccessTokenFromFiles(listOf(host, legacy)))
            host.delete()
            // 还没升级的 MaiBot 只有旧位置
            assertEquals("oldToken", MaiBotWebUi.readAccessTokenFromFiles(listOf(host, legacy)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun extractAccessTokenFromLogsFindsStartupToken() {
        val token = MaiBotWebUi.extractAccessTokenFromLogs(
            listOf(
                "[MaiBot] Process started (PID: 1234)",
                "INFO startup: WebUI access token: abc123TOKEN",
                "INFO startup: Use this token to sign in to WebUI"
            )
        )

        assertEquals("abc123TOKEN", token)
    }

    @Test
    fun extractAccessTokenFromLogsPrefersNewestToken() {
        val token = MaiBotWebUi.extractAccessTokenFromLogs(
            listOf(
                "INFO startup: WebUI access token: oldToken",
                "other log line",
                "INFO startup: WebUI access token: newToken"
            )
        )

        assertEquals("newToken", token)
    }

    @Test
    fun extractAccessTokenFromLogsIgnoresColorSequences() {
        // 终端日志里留着 SGR 颜色序列：令牌后面紧跟的 ESC[0m 不能被当成令牌的一部分
        val token = MaiBotWebUi.extractAccessTokenFromLogs(
            listOf("\u001B[33mINFO\u001B[0m startup: \u001B[1mWebUI\u001B[0m access token: \u001B[36mabc123TOKEN\u001B[0m")
        )

        assertEquals("abc123TOKEN", token)
    }

    @Test
    fun extractAccessTokenFromLogsReturnsNullWhenMissing() {
        val token = MaiBotWebUi.extractAccessTokenFromLogs(
            listOf("INFO startup: WebUI started")
        )

        assertNull(token)
    }
}
