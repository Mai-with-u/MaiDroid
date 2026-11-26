package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
