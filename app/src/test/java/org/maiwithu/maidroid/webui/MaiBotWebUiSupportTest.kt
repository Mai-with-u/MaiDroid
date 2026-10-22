package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun rewriteBottomSafeAreaAddsNavigationInsetToDeclarations() {
        // 上游悬浮底栏的 Tailwind 产物：声明里是 env 写法，选择器里的括号带反斜杠转义
        val css = ".bottom-\\[calc\\(env\\(safe-area-inset-bottom\\)\\+14px\\)\\]" +
            "{bottom:calc(env(safe-area-inset-bottom) + 14px)}"

        val rewritten = MaiBotWebUiSupport.rewriteBottomSafeArea(css, 80)

        assertEquals(
            ".bottom-\\[calc\\(env\\(safe-area-inset-bottom\\)\\+14px\\)\\]" +
                "{bottom:calc(calc(env(safe-area-inset-bottom) + 80px) + 14px)}",
            rewritten
        )
    }

    @Test
    fun rewriteBottomSafeAreaHandlesMaxShorthandFallbackAndCase() {
        val css = "a{padding-bottom:max(1.5rem,env(safe-area-inset-bottom))}" +
            "b{padding:10px 10px calc(ENV(safe-area-inset-bottom) + 12px)}" +
            "c{margin-bottom:env( safe-area-inset-bottom , 0px )}"

        val rewritten = MaiBotWebUiSupport.rewriteBottomSafeArea(css, 80)

        assertTrue(rewritten.contains("max(1.5rem,calc(env(safe-area-inset-bottom) + 80px))"))
        assertTrue(rewritten.contains("calc(calc(ENV(safe-area-inset-bottom) + 80px) + 12px)"))
        // 带回退值的写法整体保留在 calc 里
        assertTrue(rewritten.contains("calc(env( safe-area-inset-bottom , 0px ) + 80px)"))
    }

    @Test
    fun rewriteBottomSafeAreaLeavesOtherEnvAndZeroInsetUntouched() {
        val css = "a{padding-top:env(safe-area-inset-top)}b{bottom:env(safe-area-inset-bottom)}"

        // 占高为 0 时不改动任何样式；只改底部安全区，顶部安全区保持原样
        assertEquals(css, MaiBotWebUiSupport.rewriteBottomSafeArea(css, 0))
        assertEquals(css, MaiBotWebUiSupport.rewriteBottomSafeArea(css, -8))

        val rewritten = MaiBotWebUiSupport.rewriteBottomSafeArea(css, 80)
        assertTrue(rewritten.contains("padding-top:env(safe-area-inset-top)"))
        assertTrue(rewritten.contains("bottom:calc(env(safe-area-inset-bottom) + 80px)"))
    }

    @Test
    fun isLocalStylesheetUrlOnlyAcceptsLoopbackCss() {
        assertTrue(MaiBotWebUiSupport.isLocalStylesheetUrl("http", "127.0.0.1", "/assets/index-abc.css"))
        assertTrue(MaiBotWebUiSupport.isLocalStylesheetUrl("http", "localhost", "/assets/Chat.CSS"))
        assertTrue(MaiBotWebUiSupport.isLocalStylesheetUrl("https", "[::1]", "/a.css"))

        // 第三方站点、非 CSS 资源、非 http(s) 协议都不拦截
        assertFalse(MaiBotWebUiSupport.isLocalStylesheetUrl("https", "fonts.googleapis.com", "/css/a.css"))
        assertFalse(MaiBotWebUiSupport.isLocalStylesheetUrl("http", "127.0.0.1", "/assets/index.js"))
        assertFalse(MaiBotWebUiSupport.isLocalStylesheetUrl("http", "127.0.0.1", "/assets/font.woff2"))
        assertFalse(MaiBotWebUiSupport.isLocalStylesheetUrl("http", "127.0.0.1", null))
        assertFalse(MaiBotWebUiSupport.isLocalStylesheetUrl("file", "127.0.0.1", "/a.css"))
        assertFalse(MaiBotWebUiSupport.isLocalStylesheetUrl("http", null, "/a.css"))
    }

    @Test
    fun viewportPatchSkipsHeuristicPaddingWhenUpstreamDockIsPresent() {
        val script = MaiBotWebUiSupport.buildViewportPatchScript(80)

        // 上游底栏在场时不再叠加启发式留白，避免和上游按底栏位置算好的留白重复
        assertTrue(script.contains("""document.querySelector('[data-mobile-tabbar="true"]')"""))
        assertTrue(script.contains("const scrollPaddingActive = reservedBottom > 0 && !upstreamDockPresent;"))
        assertTrue(script.contains("const shouldMark = scrollPaddingActive &&"))
        assertTrue(script.contains("const reservedBottom = 80;"))
    }

    @Test
    fun viewportPatchDialogMaxHeightDoesNotSubtractNavigationInset() {
        val script = MaiBotWebUiSupport.buildViewportPatchScript(80)

        // 对话框的贴底内边距已由安全区改写让出导航栏高度，最大高度规则不能再减一次，否则底部多出空白
        val dialogRule = script.lines().single { it.contains("""'[role="dialog"]""") }
        assertTrue(dialogRule.contains("max-height:calc(var(--maidroid-webview-height) - 1rem)!important"))
        assertFalse(dialogRule.contains("reserved-bottom"))

        // 滚动区的兜底留白仍然使用导航栏高度，不受这次调整影响
        val scrollSafeRule = script.lines().single { it.contains("""'[data-maidroid-scroll-safe-area="true"]{""") }
        assertTrue(scrollSafeRule.contains("var(--maidroid-webview-reserved-bottom)"))
    }
}
