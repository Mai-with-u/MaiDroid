package org.maiwithu.maidroid.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSettingsPageTest {
    @Test
    fun `landscape window uses two panes`() {
        assertTrue(settingsUseTwoPane(widthDp = 600f, heightDp = 360f))
    }

    @Test
    fun `compact portrait window keeps single pane navigation`() {
        assertFalse(settingsUseTwoPane(widthDp = 412f, heightDp = 917f))
    }

    @Test
    fun `expanded portrait window uses two panes`() {
        assertTrue(settingsUseTwoPane(widthDp = 1000f, heightDp = 1200f))
    }

    @Test
    fun `navigation pane targets one third on a 1280dp window`() {
        assertEquals(1280f / 3f, settingsNavigationWidthDp(1280f), 0.001f)
    }

    @Test
    fun `navigation pane stays at one third on an extra wide window`() {
        assertEquals(640f, settingsNavigationWidthDp(1920f), 0f)
    }

    @Test
    fun `navigation pane remains one third on narrow landscape`() {
        assertEquals(160f, settingsNavigationWidthDp(480f), 0f)
    }
}
