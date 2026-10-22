package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiBotWebUiSupportTest {
    @Test
    fun extractAccessTokenFromLogsFindsStartupToken() {
        val token = MaiBotWebUiSupport.extractAccessTokenFromLogs(
            listOf(
                "[ProcessManager] Python process started",
                "INFO startup: WebUI access token: abc123TOKEN",
                "INFO startup: Use this token to sign in to WebUI"
            )
        )

        assertEquals("abc123TOKEN", token)
    }

    @Test
    fun extractAccessTokenFromLogsPrefersNewestToken() {
        val token = MaiBotWebUiSupport.extractAccessTokenFromLogs(
            listOf(
                "INFO startup: WebUI access token: oldToken",
                "other log line",
                "INFO startup: WebUI access token: newToken"
            )
        )

        assertEquals("newToken", token)
    }

    @Test
    fun extractAccessTokenFromLogsReturnsNullWhenMissing() {
        val token = MaiBotWebUiSupport.extractAccessTokenFromLogs(
            listOf("INFO startup: WebUI started")
        )

        assertNull(token)
    }

    @Test
    fun injectThemeBootstrapPutsViewportFixBeforeFirstScript() {
        val html = "<html><head><link rel=\"stylesheet\" href=\"/a.css\"><script src=\"/app.js\"></script></head></html>"

        val injected = MaiBotWebUiSupport.injectThemeBootstrap(html)

        // 修补脚本必须先于页面自己的脚本，否则首帧会按 vh 为 0 的样式排版
        val fixIndex = injected.indexOf("__maidroidVhFixInstalled")
        val appScriptIndex = injected.indexOf("<script src=\"/app.js\">")
        assertTrue(fixIndex in 0 until appScriptIndex)
    }

    @Test
    fun injectThemeBootstrapFallsBackToHeadAndPrefix() {
        val withHead = MaiBotWebUiSupport.injectThemeBootstrap("<head></head><body></body>")
        assertTrue(withHead.indexOf("__maidroidVhFixInstalled") < withHead.indexOf("</head>"))

        val bare = MaiBotWebUiSupport.injectThemeBootstrap("plain")
        assertTrue(bare.endsWith("plain"))
        assertTrue(bare.contains("__maidroidVhFixInstalled"))
    }
}
