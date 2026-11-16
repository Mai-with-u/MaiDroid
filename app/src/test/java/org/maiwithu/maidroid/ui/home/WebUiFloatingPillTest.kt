package org.maiwithu.maidroid.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUiFloatingPillTest {

    @Test
    fun `pill docks to nearest horizontal side across container midpoint`() {
        assertFalse(shouldDockPillRight(centerX = 539f, containerWidth = 1080f))
        assertTrue(shouldDockPillRight(centerX = 540f, containerWidth = 1080f))
        assertTrue(shouldDockPillRight(centerX = 960f, containerWidth = 1080f))
    }

    @Test
    fun `vertical bias stays clamped inside safe range`() {
        assertEquals(0f, clampPillVerticalBias(-0.4f), 0.001f)
        assertEquals(0.65f, clampPillVerticalBias(0.65f), 0.001f)
        assertEquals(1f, clampPillVerticalBias(1.3f), 0.001f)
    }
}
