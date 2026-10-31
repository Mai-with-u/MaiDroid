package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiDesktopViewportTest {
    @Test
    fun portraitKeepsMobileLayout() {
        assertNull(WebUiDesktopViewport.layoutWidthCss(widthPx = 1080, heightPx = 2265, density = 3f))
        assertNull(WebUiDesktopViewport.layoutWidthCss(widthPx = 1000, heightPx = 1000, density = 3f))
        assertNull(WebUiDesktopViewport.layoutWidthCss(widthPx = 0, heightPx = 0, density = 3f))
    }

    @Test
    fun phoneLandscapeMeetsMinimumViewport() {
        // 调试机横屏全屏、左侧让出挖孔和导航栏后的 WebView 实测 1977×1080：高度是瓶颈，宽度按 720 的高度反推
        val widthPx = 1977
        val heightPx = 1080
        val layoutWidth = WebUiDesktopViewport.layoutWidthCss(widthPx, heightPx, density = 3f)!!

        assertEquals(1318, layoutWidth)
        assertTrue(layoutWidth >= WebUiDesktopViewport.MIN_WIDTH_CSS)
        assertTrue(heightPx.toDouble() * layoutWidth / widthPx >= WebUiDesktopViewport.MIN_HEIGHT_CSS)
    }

    @Test
    fun wideShortWindowUsesMinimumWidthWhenHeightAlreadyFits() {
        // 宽高比不到 16:9 时 1280 宽已经让高度超过 720，就用 1280
        assertEquals(1280, WebUiDesktopViewport.layoutWidthCss(widthPx = 2400, heightPx = 1600, density = 2f))
    }

    @Test
    fun largeScreenIsNotZoomedIn() {
        // 平板横屏本来就有 2000 CSS px 宽，不把网页放大到 1280
        assertEquals(2000, WebUiDesktopViewport.layoutWidthCss(widthPx = 4000, heightPx = 2400, density = 2f))
    }

    @Test
    fun desktopModeReplacesViewportMetaBeforeFirstRender() {
        val html = "<head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />" +
            "<script src=\"/app.js\"></script></head>"

        val injected = WebUiResponseRewriter.injectPageBootstrap(html, desktopLayoutWidthCss = 1318)

        assertTrue(injected.contains("<meta name=\"viewport\" content=\"width=1318\">"))
        assertFalse(injected.contains("device-width"))
        assertTrue(injected.indexOf("__maidroidVhFixInstalled") < injected.indexOf("<script src=\"/app.js\">"))
    }

    @Test
    fun desktopModeAddsViewportMetaWhenPageHasNone() {
        val injected = WebUiResponseRewriter.injectPageBootstrap(
            "<head><script src=\"/app.js\"></script></head>",
            desktopLayoutWidthCss = 1400
        )

        val metaIndex = injected.indexOf("<meta name=\"viewport\" content=\"width=1400\">")
        assertTrue(metaIndex in 0 until injected.indexOf("<script src=\"/app.js\">"))
    }

    @Test
    fun mobileModeLeavesViewportMetaAlone() {
        val html = "<head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" /></head>"

        val injected = WebUiResponseRewriter.injectPageBootstrap(html)

        assertTrue(injected.contains("width=device-width, initial-scale=1.0"))
    }
}
