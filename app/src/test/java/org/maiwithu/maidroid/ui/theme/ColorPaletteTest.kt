package org.maiwithu.maidroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.settings.AppThemeMode
import org.maiwithu.maidroid.settings.resolveUseDarkTheme
import kotlin.math.max
import kotlin.math.min

/**
 * 校验应用深色与浅色主题的底色层级契约、对比度与外观模式解析：
 * - 深色模式：主页、终端、唤醒页及窗口遮罩保持 OLED 纯黑 #000000，OOBE 保留深灰 #121212；
 * - 浅色模式：页底与卡片区分层级，深墨文字在浅底/卡片/终端上的对比度满足可读标准。
 */
class ColorPaletteTest {

    @Test
    fun deepestBackgroundTokensUsePureOledBlackInDarkPalette() {
        val pureBlack = Color(0xFF000000)
        // 验证深色板的主页、终端、唤醒页最深底色均为纯黑 #000000，确保 OLED 像素可关闭发光
        assertEquals(pureBlack, DarkMaiDroidColors.pageBackground)
        assertEquals(pureBlack, DarkMaiDroidColors.terminalBackground)
        assertEquals(pureBlack, DarkMaiDroidColors.bootInk)
        assertTrue(DarkMaiDroidColors.isDark)
    }

    @Test
    fun oobeBackgroundRetainsDarkGrayForVisualSeparationInDarkPalette() {
        // 深色 OOBE 页保留原深灰底色，既能与顶部插画暗部过渡，又与灰阶卡片维持对比层级
        assertEquals(Color(0xFF121212), DarkMaiDroidColors.oobeBackground)
        assertNotEquals(DarkMaiDroidColors.pageBackground, DarkMaiDroidColors.oobeBackground)
        assertNotEquals(DarkMaiDroidColors.oobeBackground, DarkMaiDroidColors.oobeSurface)
    }

    @Test
    fun dialogBackdropScrimKeepsPureBlackRgbChannels() {
        // 弹窗半透遮罩叠在纯黑页面上时不能带有非零 RGB 亮度，避免意外点亮 OLED 子像素
        listOf(DarkMaiDroidColors.dialogBackdropScrim, LightMaiDroidColors.dialogBackdropScrim).forEach { scrim ->
            assertEquals(0f, scrim.red, 0.0001f)
            assertEquals(0f, scrim.green, 0.0001f)
            assertEquals(0f, scrim.blue, 0.0001f)
            assertTrue(scrim.alpha > 0f)
        }
    }

    @Test
    fun cardSurfacePreservesContrastAgainstBackgroundInBothPalettes() {
        // 无论深色还是浅色，卡片面、OOBE 卡片面和页底都必须保留清晰的视觉边界
        assertNotEquals(DarkMaiDroidColors.pageBackground, DarkMaiDroidColors.cardSurface)
        assertNotEquals(DarkMaiDroidColors.oobeBackground, DarkMaiDroidColors.oobeSurface)
        assertFalse(LightMaiDroidColors.isDark)
        assertNotEquals(LightMaiDroidColors.pageBackground, LightMaiDroidColors.cardSurface)
        assertNotEquals(LightMaiDroidColors.oobeBackground, LightMaiDroidColors.oobeSurface)
        assertNotEquals(LightMaiDroidColors.oobeSurface, LightMaiDroidColors.oobeSurfaceVariant)
    }

    @Test
    fun lightPaletteSatisfiesReadableContrastRatios() {
        // 浅色模式主文字、次要文字和终端正文在各自底色上均须满足可辨识对比度（≥ 4.5:1）
        assertTrue(contrastRatio(LightMaiDroidColors.textPrimary, LightMaiDroidColors.pageBackground) >= 12f)
        assertTrue(contrastRatio(LightMaiDroidColors.textPrimary, LightMaiDroidColors.cardSurface) >= 14f)
        assertTrue(contrastRatio(LightMaiDroidColors.textMuted, LightMaiDroidColors.cardSurface) >= 4.5f)
        assertTrue(contrastRatio(LightMaiDroidColors.oobeTextPrimary, LightMaiDroidColors.oobeSurface) >= 14f)
        assertTrue(contrastRatio(LightMaiDroidColors.terminalText, LightMaiDroidColors.terminalBackground) >= 12f)
        assertTrue(contrastRatio(LightMaiDroidColors.bootText, LightMaiDroidColors.bootInk) >= 12f)
    }

    @Test
    fun appThemeModeParsesStorageValuesAndResolvesEffectiveDarkFlag() {
        assertEquals(AppThemeMode.System, AppThemeMode.fromStorage(null))
        assertEquals(AppThemeMode.System, AppThemeMode.fromStorage("unknown"))
        assertEquals(AppThemeMode.Light, AppThemeMode.fromStorage("light"))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromStorage("dark"))

        assertTrue(resolveUseDarkTheme(AppThemeMode.System, systemInDarkTheme = true))
        assertFalse(resolveUseDarkTheme(AppThemeMode.System, systemInDarkTheme = false))
        assertFalse(resolveUseDarkTheme(AppThemeMode.Light, systemInDarkTheme = true))
        assertTrue(resolveUseDarkTheme(AppThemeMode.Dark, systemInDarkTheme = false))
    }

    private fun contrastRatio(foreground: Color, background: Color): Float {
        val l1 = foreground.luminance() + 0.05f
        val l2 = background.luminance() + 0.05f
        return max(l1, l2) / min(l1, l2)
    }
}
