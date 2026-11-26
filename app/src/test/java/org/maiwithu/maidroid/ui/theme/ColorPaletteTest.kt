package org.maiwithu.maidroid.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校验应用深色主题的底色层级契约：
 * 主页、终端、唤醒页及窗口遮罩保持 OLED 纯黑 #000000，OOBE 保留深灰 #121212 以维持与插画暗部及灰阶卡片的层次。
 */
class ColorPaletteTest {

    @Test
    fun deepestBackgroundTokensUsePureOledBlack() {
        val pureBlack = Color(0xFF000000)
        // 验证主页、终端、唤醒页最深底色均为纯黑 #000000，确保 OLED 像素可关闭发光
        assertEquals(pureBlack, PageBackground)
        assertEquals(pureBlack, TerminalBackground)
        assertEquals(pureBlack, BootInk)
    }

    @Test
    fun oobeBackgroundRetainsDarkGrayForVisualSeparation() {
        // OOBE 页保留原深灰底色，既能与顶部插画暗部过渡，又与灰阶卡片维持对比层级
        assertEquals(Color(0xFF121212), OobeBackground)
        assertNotEquals(PageBackground, OobeBackground)
        assertNotEquals(OobeBackground, OobeSurface)
    }

    @Test
    fun dialogBackdropScrimKeepsPureBlackRgbChannels() {
        // 弹窗半透遮罩叠在纯黑页面上时不能带有非零 RGB 亮度，避免意外点亮 OLED 子像素
        assertEquals(0f, DialogBackdropScrim.red, 0.0001f)
        assertEquals(0f, DialogBackdropScrim.green, 0.0001f)
        assertEquals(0f, DialogBackdropScrim.blue, 0.0001f)
        assertTrue(DialogBackdropScrim.alpha > 0f)
    }

    @Test
    fun cardSurfacePreservesContrastAgainstPureBlackBackground() {
        // 卡片层维持原有深灰，保证与纯黑底层背景的视觉边界清晰
        assertNotEquals(PageBackground, CardSurface)
        assertNotEquals(OobeBackground, OobeSurface)
    }
}
