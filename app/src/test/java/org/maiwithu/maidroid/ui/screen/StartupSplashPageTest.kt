package org.maiwithu.maidroid.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupSplashPageTest {
    @Test
    fun `phone portrait keeps the original splash layout`() {
        assertFalse(startupSplashUsesAdaptiveLayout(widthDp = 360f, heightDp = 800f))
        assertFalse(startupSplashUsesAdaptiveLayout(widthDp = 412f, heightDp = 917f))
        assertFalse(startupSplashUsesAdaptiveLayout(widthDp = 599f, heightDp = 1000f))
        assertFalse(startupSplashUsesAdaptiveLayout(widthDp = 599f, heightDp = 599f))
    }

    @Test
    fun `landscape and large screens use height based splash layout`() {
        assertTrue(startupSplashUsesAdaptiveLayout(widthDp = 480f, heightDp = 320f))
        assertTrue(startupSplashUsesAdaptiveLayout(widthDp = 600f, heightDp = 1200f))
        assertTrue(startupSplashUsesAdaptiveLayout(widthDp = 600f, heightDp = 601f))
        assertTrue(startupSplashUsesAdaptiveLayout(widthDp = 600f, heightDp = 600f))
        assertTrue(startupSplashUsesAdaptiveLayout(widthDp = 800f, heightDp = 1280f))
        assertTrue(startupSplashUsesAdaptiveLayout(widthDp = 1280f, heightDp = 800f))
    }

    @Test
    fun `design geometry remains unchanged at its reference size`() {
        val compactGeometry = startupSplashCompactGeometry(widthDp = 412f, heightDp = 917f)
        val geometry = startupSplashAdaptiveGeometry(widthDp = 412f, heightDp = 917f)

        assertEquals(1f, compactGeometry.scaleX, 0f)
        assertEquals(1f, compactGeometry.scaleY, 0f)
        assertEquals(1f, compactGeometry.contentScale, 0f)
        assertEquals(-6f, compactGeometry.artLeftDp, 0f)
        assertEquals(424f, compactGeometry.artWidthDp, 0f)
        assertEquals(917f, compactGeometry.artHeightDp, 0f)
        assertEquals(49f, compactGeometry.infoLeftDp, 0f)
        assertEquals(773f, compactGeometry.infoTopDp, 0f)

        assertEquals(1f, geometry.scale, 0f)
        assertEquals(1f, geometry.infoScale, 0f)
        assertEquals(0f, geometry.sceneLeftDp, 0f)
        assertEquals(-6f, geometry.artLeftDp, 0f)
        assertEquals(424f, geometry.artWidthDp, 0f)
        assertEquals(917f, geometry.artHeightDp, 0f)
        assertEquals(49f, geometry.infoLeftDp, 0f)
        assertEquals(773f, geometry.infoTopDp, 0f)
        assertEquals(314f, geometry.infoWidthDp, 0f)
        assertEquals(96f, geometry.infoHeightDp, 0f)
    }

    @Test
    fun `adaptive art fills height and welcome card stays centered`() {
        listOf(
            480f to 320f,
            600f to 1200f,
            800f to 1280f,
            1280f to 800f
        ).forEach { (width, height) ->
            val geometry = startupSplashAdaptiveGeometry(widthDp = width, heightDp = height)
            val leftArtOverflow = geometry.artLeftDp
            val rightArtOverflow = width - geometry.artLeftDp - geometry.artWidthDp
            val infoRight = geometry.infoLeftDp + geometry.infoWidthDp
            val infoBottom = geometry.infoTopDp + geometry.infoHeightDp

            assertEquals(height, geometry.artHeightDp, 0.001f)
            assertEquals(424f / 917f, geometry.artWidthDp / geometry.artHeightDp, 0.001f)
            assertEquals(leftArtOverflow, rightArtOverflow, 0.001f)
            assertEquals(width / 2f, geometry.infoLeftDp + geometry.infoWidthDp / 2f, 0.001f)
            assertEquals(55f * geometry.scale, geometry.infoLeftDp - geometry.artLeftDp, 0.001f)
            assertTrue(geometry.infoLeftDp >= 0f)
            assertTrue(infoRight <= width)
            assertTrue(geometry.infoTopDp >= 0f)
            assertTrue(infoBottom <= height)
        }
    }

    @Test
    fun `welcome card remains inside extremely tall large windows`() {
        listOf(600f to 1800f, 600f to 2000f).forEach { (width, height) ->
            val geometry = startupSplashAdaptiveGeometry(widthDp = width, heightDp = height)

            assertTrue(geometry.infoScale < geometry.scale)
            assertEquals(16f, geometry.infoLeftDp, 0.001f)
            assertEquals(width / 2f, geometry.infoLeftDp + geometry.infoWidthDp / 2f, 0.001f)
            assertTrue(geometry.infoTopDp >= 0f)
            assertTrue(geometry.infoTopDp + geometry.infoHeightDp <= height)
            assertTrue(geometry.infoLeftDp + geometry.infoWidthDp <= width)
        }
    }
}
