package org.maiwithu.maidroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.ui.component.HomeTwoPaneSpec

class SettingsPageTest {
    @Test
    fun `landscape window uses two panes when wide enough`() {
        assertTrue(settingsUseTwoPane(widthDp = 600f, heightDp = 360f))
        assertTrue(settingsUseTwoPane(widthDp = 520f, heightDp = 360f))
    }

    @Test
    fun `narrow landscape split window falls back to single pane`() {
        assertFalse(settingsUseTwoPane(widthDp = 480f, heightDp = 360f))
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
    fun `navigation pane locks to unified 38 percent width across breakpoints`() {
        // 与消息平台、扩展组件页共享 HomeTwoPaneSpec 的 38% 左栏基准
        assertEquals(1280f * 0.38f, settingsNavigationWidthDp(1280f), 0.001f)
        assertEquals(HomeTwoPaneSpec.leftPaneWidthDp(1280f), settingsNavigationWidthDp(1280f), 0.001f)
        assertEquals(1920f * 0.38f, settingsNavigationWidthDp(1920f), 0.001f)
        assertEquals(HomeTwoPaneSpec.leftPaneWidthDp(1920f), settingsNavigationWidthDp(1920f), 0.001f)
        assertEquals(480f * 0.38f, settingsNavigationWidthDp(480f), 0.001f)
        assertEquals(HomeTwoPaneSpec.leftPaneWidthDp(480f), settingsNavigationWidthDp(480f), 0.001f)
    }
}
