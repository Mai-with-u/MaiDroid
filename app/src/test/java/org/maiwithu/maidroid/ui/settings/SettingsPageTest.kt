package org.maiwithu.maidroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `two pane detail selection toggles back to centered empty icon while portrait enters detail`() {
        // 首次从默认空态（null）点击“权限管理”进入详情
        assertEquals(
            "permissions",
            resolveSettingsDetailSelection(currentDetail = null, targetDetail = "permissions", useTwoPane = true)
        )
        // 双栏模式下再次点击当前已选详情可收起回到右栏居中液态玻璃图标空态
        assertNull(
            resolveSettingsDetailSelection(
                currentDetail = "permissions",
                targetDetail = "permissions",
                useTwoPane = true
            )
        )
        // 竖屏单列模式下保持进入目标详情页
        assertEquals(
            "permissions",
            resolveSettingsDetailSelection(
                currentDetail = "permissions",
                targetDetail = "permissions",
                useTwoPane = false
            )
        )
    }
}
