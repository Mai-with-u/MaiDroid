package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiDialogLayoutTest {

    @Test
    fun `compact dialogs stay narrow across portrait and phone landscape`() {
        val portrait = resolveMaiDialogLayout(
            availableWidthDp = 360f,
            availableHeightDp = 720f,
            size = MaiDialogSize.Compact,
            hasSecondaryContent = false
        )
        assertFalse(portrait.isPhoneLandscape)
        assertFalse(portrait.useTwoColumns)
        assertEquals(331.2f, portrait.maxWidthDp, 0.1f)
        assertEquals(619.2f, portrait.maxHeightDp, 0.1f)

        // D 类确认弹窗在横屏下保持 <= 380dp 小窗，绝不被拉宽成空旷黑面板
        val landscape = resolveMaiDialogLayout(
            availableWidthDp = 720f,
            availableHeightDp = 340f,
            size = MaiDialogSize.Compact,
            hasSecondaryContent = false
        )
        assertTrue(landscape.isPhoneLandscape)
        assertFalse(landscape.useTwoColumns)
        assertEquals(380f, landscape.maxWidthDp, 0.1f)
        assertEquals(320f, landscape.maxHeightDp, 0.1f)

        // 竖屏弹出软键盘后剩余高度缩至 320dp：按比例压缩，绝不硬撑 300dp 顶出边界
        val keyboard = resolveMaiDialogLayout(
            availableWidthDp = 360f,
            availableHeightDp = 320f,
            size = MaiDialogSize.Compact,
            hasSecondaryContent = false
        )
        assertEquals(275.2f, keyboard.maxHeightDp, 0.1f)
    }

    @Test
    fun `medium and expanded sizes adapt cleanly between single and two column layouts`() {
        // B/C 类（如清理空间、二维码）：横屏用 520dp 中等宽度
        val mediumLandscape = resolveMaiDialogLayout(
            availableWidthDp = 720f,
            availableHeightDp = 340f,
            size = MaiDialogSize.Medium,
            hasSecondaryContent = false
        )
        assertTrue(mediumLandscape.isPhoneLandscape)
        assertFalse(mediumLandscape.useTwoColumns)
        assertEquals(520f, mediumLandscape.maxWidthDp, 0.1f)
        assertEquals(320f, mediumLandscape.maxHeightDp, 0.1f)

        // A 类带第二列内容：横屏放宽至 680dp 并开启左右两列
        val expandedLandscape = resolveMaiDialogLayout(
            availableWidthDp = 720f,
            availableHeightDp = 340f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertTrue(expandedLandscape.isPhoneLandscape)
        assertTrue(expandedLandscape.useTwoColumns)
        assertEquals(680f, expandedLandscape.maxWidthDp, 0.1f)
        assertEquals(320f, expandedLandscape.maxHeightDp, 0.1f)

        // 横屏软键盘弹起后剩余高 190dp：仍保持两列各自滚动且严守 170dp 高度上限
        val landscapeKeyboard = resolveMaiDialogLayout(
            availableWidthDp = 720f,
            availableHeightDp = 190f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertTrue(landscapeKeyboard.isPhoneLandscape)
        assertTrue(landscapeKeyboard.useTwoColumns)
        assertEquals(170f, landscapeKeyboard.maxHeightDp, 0.1f)

        // 竖屏即使有 secondaryContent 也维持单列竖排
        val expandedPortrait = resolveMaiDialogLayout(
            availableWidthDp = 360f,
            availableHeightDp = 720f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertFalse(expandedPortrait.isPhoneLandscape)
        assertFalse(expandedPortrait.useTwoColumns)
        assertEquals(331.2f, expandedPortrait.maxWidthDp, 0.1f)
    }

    @Test
    fun `tablet viewports widen expanded dialogs and enable two columns when appropriate`() {
        val tabletPortrait = resolveMaiDialogLayout(
            availableWidthDp = 768f,
            availableHeightDp = 980f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertFalse(tabletPortrait.isPhoneLandscape)
        assertTrue(tabletPortrait.useTwoColumns)
        assertEquals(600f, tabletPortrait.maxWidthDp, 0.1f)
        assertEquals(680f, tabletPortrait.maxHeightDp, 0.1f)

        val tabletLandscape = resolveMaiDialogLayout(
            availableWidthDp = 1000f,
            availableHeightDp = 640f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertFalse(tabletLandscape.isPhoneLandscape)
        assertTrue(tabletLandscape.useTwoColumns)
        assertEquals(600f, tabletLandscape.maxWidthDp, 0.1f)
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
