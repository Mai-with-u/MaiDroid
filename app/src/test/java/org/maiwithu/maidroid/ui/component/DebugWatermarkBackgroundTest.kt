package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.ui.theme.DarkMaiDroidColors
import org.maiwithu.maidroid.ui.theme.LightMaiDroidColors
import kotlin.math.roundToInt

class DebugWatermarkBackgroundTest {

    @Test
    fun `watermark text preserves exact leading trailing spaces and Chinese punctuation`() {
        assertEquals(
            "    MaiDroid · 开发中内容，请以实际上线内容为准！    ",
            DebugWatermarkText
        )
    }

    @Test
    fun `watermark activates exclusively for debug builds`() {
        assertTrue(shouldShowDebugBackgroundWatermark(isDebugBuild = true))
        assertFalse(shouldShowDebugBackgroundWatermark(isDebugBuild = false))
    }

    @Test
    fun `tile dimensions round up and clamp invalid non-positive metrics`() {
        assertEquals(821 to 336, computeDebugWatermarkTileDimensions(textWidthPx = 820.2f, rowSpacingPx = 168f))
        assertEquals(1 to 1, computeDebugWatermarkTileDimensions(textWidthPx = 0f, rowSpacingPx = -10f))
        assertEquals(1 to 1, computeDebugWatermarkTileDimensions(textWidthPx = Float.NaN, rowSpacingPx = Float.POSITIVE_INFINITY))
    }

    @Test
    fun `watermark ink switches between white on dark and charcoal ink on light surface`() {
        assertFalse(isLightSurfaceColor(DarkMaiDroidColors.pageBackground))
        assertTrue(isLightSurfaceColor(LightMaiDroidColors.pageBackground))

        val darkInk = resolveDebugWatermarkInkArgb(DarkMaiDroidColors.pageBackground)
        assertEquals((DebugWatermarkAlpha * 255f).roundToInt().coerceIn(0, 255), (darkInk ushr 24) and 0xFF)
        assertEquals(0xFFFFFF, darkInk and 0xFFFFFF)

        val lightInk = resolveDebugWatermarkInkArgb(LightMaiDroidColors.pageBackground)
        assertEquals((DebugWatermarkLightBgAlpha * 255f).roundToInt().coerceIn(0, 255), (lightInk ushr 24) and 0xFF)
        assertEquals(0x0F172A, lightInk and 0xFFFFFF)
    }
}
