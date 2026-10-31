package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiBotWebUiTest {
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

    @Test
    fun injectThemeBootstrapPutsViewportFixBeforeFirstScript() {
        val html = "<html><head><link rel=\"stylesheet\" href=\"/a.css\"><script src=\"/app.js\"></script></head></html>"

        val injected = WebUiResponseRewriter.injectPageBootstrap(html)

        // 修补脚本必须先于页面自己的脚本，否则首帧会按 vh 为 0 的样式排版
        val fixIndex = injected.indexOf("__maidroidVhFixInstalled")
        val appScriptIndex = injected.indexOf("<script src=\"/app.js\">")
        assertTrue(fixIndex in 0 until appScriptIndex)
    }

    @Test
    fun injectPageBootstrapInstallsDownloadHookBeforeFirstScript() {
        val html = "<html><head><script src=\"/app.js\"></script></head></html>"

        val injected = WebUiResponseRewriter.injectPageBootstrap(html)

        // 下载拦截要在页面生成导出文件之前包住 URL.createObjectURL，所以也得先于页面脚本
        val hookIndex = injected.indexOf("URL.createObjectURL = function")
        val appScriptIndex = injected.indexOf("<script src=\"/app.js\">")
        assertTrue(hookIndex in 0 until appScriptIndex)
        assertTrue(injected.contains("window.${WebUiDownloadScripts.BRIDGE_NAME}"))
    }

    @Test
    fun saveUrlScriptQuotesUrlAndFileName() {
        // 地址和文件名按 JSON 字符串嵌进脚本，引号和反斜杠不会截断脚本
        val script = WebUiDownloadScripts.saveUrlScript("blob:http://127.0.0.1:8001/abc", "a\"b\\c.txt")

        assertTrue(script.endsWith("saveUrl(\"blob:http://127.0.0.1:8001/abc\", \"a\\\"b\\\\c.txt\");"))
    }

    @Test
    fun themeBootstrapOnlyFillsMissingThemeSettings() {
        // 用户在 WebUI 外观设置里换过主题后，每次启动不能再被改回“未来复古”：只在键为空时写默认值
        val injected = WebUiResponseRewriter.injectPageBootstrap("<head></head>")

        assertTrue(injected.contains("localStorage.getItem('maibot-theme-mode') === null"))
        assertTrue(injected.contains("localStorage.getItem('maibot-theme-dashboard-style') === null"))
    }

    @Test
    fun injectThemeBootstrapFallsBackToHeadAndPrefix() {
        val withHead = WebUiResponseRewriter.injectPageBootstrap("<head></head><body></body>")
        assertTrue(withHead.indexOf("__maidroidVhFixInstalled") < withHead.indexOf("</head>"))

        val bare = WebUiResponseRewriter.injectPageBootstrap("plain")
        assertTrue(bare.endsWith("plain"))
        assertTrue(bare.contains("__maidroidVhFixInstalled"))
    }

    @Test
    fun viewportPatchLeavesPageScrollAreasAtTheirOwnSize() {
        val script = WebUiInjectedScripts.viewportPatch()

        // 普通滚动区不再被统一封顶成一屏高：真机竖屏上 max-h-80 的表格被撑成整屏，单指只能滚表格
        assertFalse(script.contains("max-height:var(--maidroid-webview-height)"))
        // 对话框最高为视口减 1rem；只认对话框本身，不按类名子串匹配（WebUI 类名里到处是 --layout-sidebar-* 变量名）
        val dialogRule = script.lines().single { it.contains("""'[role="dialog"]""") }
        assertTrue(dialogRule.contains("max-height:calc(var(--maidroid-webview-height) - 1rem)!important"))
        assertFalse(dialogRule.contains("[class*="))
    }

    @Test
    fun viewportPatchDoesNotRescanPageOnDomChanges() {
        val script = WebUiInjectedScripts.viewportPatch()

        // 导航栏留白去掉后，不再盯 DOM 变化、每次把全页元素量一遍；只在视口尺寸变化时重算高度
        assertFalse(script.contains("MutationObserver"))
        assertFalse(script.contains("querySelectorAll('body *')"))
        assertFalse(script.contains("reserved"))
        assertTrue(script.contains("visualViewport.addEventListener('resize', apply"))
    }
}
