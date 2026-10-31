package org.maiwithu.maidroid.webui

import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiTwoFingerScrollTest {
    @Test
    fun beginScriptInstallsOnceThenBeginsAtFocus() {
        val script = WebUiTwoFingerScroll.beginScript(540.5f, 1200f, 1080)
        // 先装脚本（已装过就跳过），再按两指中点开始
        assertTrue(script.contains("if (window.__maidroidTwoFingerScroll) return;"))
        assertTrue(script.trimEnd().endsWith("window.__maidroidTwoFingerScroll.begin(540.5, 1200.0, 1080);"))
    }

    @Test
    fun moveScriptFallsBackWhenPageReloaded() {
        // 页面刚刷新、脚本还没装时回 false，惯性随之停下
        val script = WebUiTwoFingerScroll.moveScript(-12.5f)
        assertTrue(script.contains("move(-12.5)"))
        assertTrue(script.endsWith(": false"))
    }

    @Test
    fun numbersKeepDotDecimalUnderCommaLocale() {
        // 德语等系统语言的小数点是逗号，拼进脚本会变成两个参数；这里确认不受系统语言影响
        val original = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            assertTrue(WebUiTwoFingerScroll.moveScript(3.25f).contains("move(3.25)"))
            assertTrue(WebUiTwoFingerScroll.beginScript(1.5f, 2.5f, 100).contains("begin(1.5, 2.5, 100)"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
