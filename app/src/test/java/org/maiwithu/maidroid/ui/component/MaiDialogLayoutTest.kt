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

        // 典型手机横屏（扣挖孔后约 760×360）：短确认框封顶 400dp 宽、上下各留 24dp 背景
        val landscape = resolveMaiDialogLayout(
            availableWidthDp = 760f,
            availableHeightDp = 360f,
            size = MaiDialogSize.Compact,
            hasSecondaryContent = false
        )
        assertTrue(landscape.isPhoneLandscape)
        assertFalse(landscape.useTwoColumns)
        assertEquals(400f, landscape.maxWidthDp, 0.1f)
        assertEquals(312f, landscape.maxHeightDp, 0.1f)

        // 竖屏弹出软键盘后剩余高度缩至 320dp：按比例压缩，绝不硬撑 300dp 顶出边界
        val keyboard = resolveMaiDialogLayout(
            availableWidthDp = 360f,
            availableHeightDp = 320f,
            size = MaiDialogSize.Compact,
            hasSecondaryContent = false
        )
        assertFalse(keyboard.isPhoneLandscape)
        assertEquals(275.2f, keyboard.maxHeightDp, 0.1f)
    }

    @Test
    fun `medium and expanded sizes adapt cleanly between single and two column layouts`() {
        // 清理空间、二维码等中等弹窗：横屏封顶 500dp 宽，单列
        val mediumLandscape = resolveMaiDialogLayout(
            availableWidthDp = 760f,
            availableHeightDp = 360f,
            size = MaiDialogSize.Medium,
            hasSecondaryContent = false
        )
        assertTrue(mediumLandscape.isPhoneLandscape)
        assertFalse(mediumLandscape.useTwoColumns)
        assertEquals(500f, mediumLandscape.maxWidthDp, 0.1f)
        assertEquals(312f, mediumLandscape.maxHeightDp, 0.1f)

        // 带第二列内容的扩展弹窗：横屏封顶 620dp 宽并开启左右两列，高度封顶在 312dp（上下各留 24dp）
        val expandedLandscape = resolveMaiDialogLayout(
            availableWidthDp = 760f,
            availableHeightDp = 360f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertTrue(expandedLandscape.isPhoneLandscape)
        assertTrue(expandedLandscape.useTwoColumns)
        assertEquals(620f, expandedLandscape.maxWidthDp, 0.1f)
        assertEquals(312f, expandedLandscape.maxHeightDp, 0.1f)

        // 稍窄的横屏（720×340）：宽度按 82% 收敛，上下边距按高度收窄到 20dp
        val narrowerLandscape = resolveMaiDialogLayout(
            availableWidthDp = 720f,
            availableHeightDp = 340f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertTrue(narrowerLandscape.useTwoColumns)
        assertEquals(590.4f, narrowerLandscape.maxWidthDp, 0.1f)
        assertEquals(300f, narrowerLandscape.maxHeightDp, 0.1f)

        // 横屏软键盘弹起后剩余高 190dp：边距收窄到 6dp 让位给内容，仍保持两列各自滚动
        val landscapeKeyboard = resolveMaiDialogLayout(
            availableWidthDp = 760f,
            availableHeightDp = 190f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertTrue(landscapeKeyboard.isPhoneLandscape)
        assertTrue(landscapeKeyboard.useTwoColumns)
        assertEquals(178f, landscapeKeyboard.maxHeightDp, 0.1f)

        // 很窄的横屏放不下两列可读宽度时退回单列
        val smallLandscape = resolveMaiDialogLayout(
            availableWidthDp = 600f,
            availableHeightDp = 340f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertTrue(smallLandscape.isPhoneLandscape)
        assertFalse(smallLandscape.useTwoColumns)
        assertEquals(492f, smallLandscape.maxWidthDp, 0.1f)

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
    fun `phone landscape dialogs always leave visible breathing room around the card`() {
        val viewportW = 760f
        val viewportH = 360f
        MaiDialogSize.entries.forEach { size ->
            val spec = resolveMaiDialogLayout(
                availableWidthDp = viewportW,
                availableHeightDp = viewportH,
                size = size,
                hasSecondaryContent = true
            )
            // 左右各至少 64dp、上下各至少 24dp 的背景页露出，一眼看出是悬浮卡片而不是整页黑板
            assertTrue("$size 左右留白不足", (viewportW - spec.maxWidthDp) / 2f >= 64f)
            assertTrue("$size 上下留白不足", (viewportH - spec.maxHeightDp) / 2f >= 24f)
        }
    }

    @Test
    fun `landscape vertical margin shrinks as the viewport gets shorter`() {
        assertEquals(24f, landscapeVerticalMarginDp(360f), 0.01f)
        assertEquals(24f, landscapeVerticalMarginDp(420f), 0.01f)
        assertEquals(20f, landscapeVerticalMarginDp(340f), 0.01f)
        assertEquals(6f, landscapeVerticalMarginDp(190f), 0.01f)
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
        assertEquals(614.4f, tabletPortrait.maxWidthDp, 0.1f)
        assertEquals(680f, tabletPortrait.maxHeightDp, 0.1f)

        val tabletLandscape = resolveMaiDialogLayout(
            availableWidthDp = 1000f,
            availableHeightDp = 640f,
            size = MaiDialogSize.Expanded,
            hasSecondaryContent = true
        )
        assertFalse(tabletLandscape.isPhoneLandscape)
        assertTrue(tabletLandscape.useTwoColumns)
        assertEquals(640f, tabletLandscape.maxWidthDp, 0.1f)
        assertEquals(640f * 0.84f, tabletLandscape.maxHeightDp, 0.1f)

        // 平板上的中等弹窗封顶 520dp，短确认保持 380dp 小窗
        val tabletMedium = resolveMaiDialogLayout(
            availableWidthDp = 1000f,
            availableHeightDp = 640f,
            size = MaiDialogSize.Medium
        )
        assertEquals(520f, tabletMedium.maxWidthDp, 0.1f)
        val tabletCompact = resolveMaiDialogLayout(
            availableWidthDp = 1000f,
            availableHeightDp = 640f,
            size = MaiDialogSize.Compact
        )
        assertEquals(380f, tabletCompact.maxWidthDp, 0.1f)
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
