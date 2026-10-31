package org.maiwithu.maidroid.webui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebUiPageBackgroundTest {
    @Test
    fun parsesReportedColor() {
        // “未来复古”深色主题实测的 body 底色 rgb(47, 44, 40)，不透明
        val argb = WebUiPageBackground.parseReport("${WebUiPageBackground.REPORT_PREFIX}47,44,40,255")
        assertEquals(0xFF2F2C28.toInt(), argb)
    }

    @Test
    fun keepsAlphaOfTranslucentColor() {
        val argb = WebUiPageBackground.parseReport("${WebUiPageBackground.REPORT_PREFIX}255, 255, 255, 128")
        assertEquals(0x80FFFFFF.toInt(), argb)
    }

    @Test
    fun ignoresOrdinaryConsoleMessages() {
        // 网页自己的日志原样留给 logcat
        assertNull(WebUiPageBackground.parseReport("47,44,40,255"))
        assertNull(WebUiPageBackground.parseReport("[vite] connected."))
    }

    @Test
    fun rejectsMalformedReports() {
        val prefix = WebUiPageBackground.REPORT_PREFIX
        assertNull(WebUiPageBackground.parseReport("${prefix}47,44,40"))
        assertNull(WebUiPageBackground.parseReport("${prefix}47,44,40,255,0"))
        assertNull(WebUiPageBackground.parseReport("${prefix}47,44,300,255"))
        assertNull(WebUiPageBackground.parseReport("${prefix}rgb(47, 44, 40)"))
    }
}
