package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiDialogLayoutTest {

    @Test
    fun `portrait phone keeps single column and shrinks below 300dp when keyboard opens`() {
        val normal = resolveMaiDialogLayout(availableWidthDp = 360f, availableHeightDp = 720f)
        assertEquals(MaiDialogLayoutMode.Portrait, normal.mode)
        assertEquals(331.2f, normal.maxWidthDp, 0.1f)
        assertEquals(619.2f, normal.maxHeightDp, 0.1f)

        // 竖屏弹出软键盘后剩余高度 320dp：必须按 86% 跟随缩矮，绝不硬撑 300dp 顶出视口
        val withKeyboard = resolveMaiDialogLayout(availableWidthDp = 360f, availableHeightDp = 320f)
        assertEquals(MaiDialogLayoutMode.Portrait, withKeyboard.mode)
        assertEquals(275.2f, withKeyboard.maxHeightDp, 0.1f)
    }

    @Test
    fun `landscape phone switches to split pane with clamped left column width`() {
        val standardLandscape = resolveMaiDialogLayout(availableWidthDp = 710f, availableHeightDp = 336f)
        assertEquals(MaiDialogLayoutMode.LandscapeSplit, standardLandscape.mode)
        assertEquals(660f, standardLandscape.maxWidthDp, 0.1f)
        assertEquals(336f * 0.94f, standardLandscape.maxHeightDp, 0.1f)
        assertEquals(237.6f, standardLandscape.leftPaneWidthDp, 0.1f)

        // 较小横屏窗口同样进入双栏并保持左栏至少 208dp
        val compactLandscape = resolveMaiDialogLayout(availableWidthDp = 560f, availableHeightDp = 310f)
        assertEquals(MaiDialogLayoutMode.LandscapeSplit, compactLandscape.mode)
        assertEquals(526.4f, compactLandscape.maxWidthDp, 0.1f)
        assertEquals(208f, compactLandscape.leftPaneWidthDp, 0.1f)

        // 横屏弹软键盘后剩余高度极小时仍保留双栏并收矮到安全区内
        val landscapeIme = resolveMaiDialogLayout(availableWidthDp = 710f, availableHeightDp = 190f)
        assertEquals(MaiDialogLayoutMode.LandscapeSplit, landscapeIme.mode)
        assertEquals(178.6f, landscapeIme.maxHeightDp, 0.1f)
    }

    @Test
    fun `tablet portrait and landscape use expanded centered dialog width`() {
        val tabletPortrait = resolveMaiDialogLayout(availableWidthDp = 768f, availableHeightDp = 980f)
        assertEquals(MaiDialogLayoutMode.Expanded, tabletPortrait.mode)
        assertEquals(520f, tabletPortrait.maxWidthDp, 0.1f)
        assertEquals(680f, tabletPortrait.maxHeightDp, 0.1f)

        val tabletLandscape = resolveMaiDialogLayout(availableWidthDp = 1000f, availableHeightDp = 640f)
        assertEquals(MaiDialogLayoutMode.Expanded, tabletLandscape.mode)
        assertEquals(520f, tabletLandscape.maxWidthDp, 0.1f)
        assertEquals(640f * 0.84f, tabletLandscape.maxHeightDp, 0.1f)
    }

    @Test
    fun `button row stacks vertically when intrinsic widths exceed weighted slot width`() {
        assertFalse(shouldStackDialogButtons(listOf(180), listOf(1f), spacingPx = 20, maxWidthPx = 150))
        assertFalse(shouldStackDialogButtons(listOf(120, 140), listOf(1f, 1f), spacingPx = 20, maxWidthPx = 400))

        // 合计超宽 -> 自动堆叠
        assertTrue(shouldStackDialogButtons(listOf(220, 220), listOf(1f, 1f), spacingPx = 20, maxWidthPx = 400))

        // 合计虽未超限，但在 1:1 权重分配下右侧长按钮分不到固有宽度 -> 提前堆叠避免出现省略号
        assertTrue(shouldStackDialogButtons(listOf(80, 220), listOf(1f, 1f), spacingPx = 20, maxWidthPx = 400))
        // 调高右侧按钮权重后可优雅并排
        assertFalse(shouldStackDialogButtons(listOf(80, 220), listOf(0.6f, 1.4f), spacingPx = 20, maxWidthPx = 400))
    }
}
