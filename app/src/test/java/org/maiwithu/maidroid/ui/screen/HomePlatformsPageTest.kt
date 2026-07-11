package org.maiwithu.maidroid.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePlatformsPageTest {

    @Test
    fun `column count keeps cards above their minimum width`() {
        assertEquals(1, calculatePlatformGridColumnCount(555f))
        assertEquals(2, calculatePlatformGridColumnCount(556f))
    }

    @Test
    fun `column count adds columns when cards would exceed their maximum width`() {
        assertEquals(2, calculatePlatformGridColumnCount(852f))
        assertEquals(3, calculatePlatformGridColumnCount(853f))
        assertEquals(3, calculatePlatformGridColumnCount(1_284f))
        assertEquals(4, calculatePlatformGridColumnCount(1_285f))
    }

    @Test
    fun `column count handles narrow and very wide content widths`() {
        assertEquals(1, calculatePlatformGridColumnCount(-1f))
        assertEquals(1, calculatePlatformGridColumnCount(328f))
        assertEquals(5, calculatePlatformGridColumnCount(1_888f))
    }
}
