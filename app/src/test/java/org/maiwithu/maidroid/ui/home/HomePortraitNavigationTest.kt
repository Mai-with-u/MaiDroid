package org.maiwithu.maidroid.ui.home

import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePortraitNavigationTest {

    @Test
    fun `default dock side is left edge`() {
        assertEquals(PillDockSide.Left, DefaultPillDockSide)
    }

    @Test
    fun `pill docks to nearest horizontal side across container midpoint`() {
        assertEquals(PillDockSide.Left, resolvePillDockSide(centerX = 539f, containerWidth = 1080f))
        assertEquals(PillDockSide.Right, resolvePillDockSide(centerX = 540f, containerWidth = 1080f))
        assertEquals(PillDockSide.Right, resolvePillDockSide(centerX = 960f, containerWidth = 1080f))
    }

    @Test
    fun `vertical bias stays clamped inside safe range`() {
        assertEquals(0f, clampPillVerticalBias(-0.4f), 0.001f)
        assertEquals(0.65f, clampPillVerticalBias(0.65f), 0.001f)
        assertEquals(1f, clampPillVerticalBias(1.3f), 0.001f)
    }

    @Test
    fun `collapsed pill keeps unified 64dp width with expanded pill and shifts up only near bottom`() {
        assertEquals(HomeNavUnitSizeDp, CollapsedPillWidthDp, 0.001f)
        assertEquals(CollapsedPillWidthDp, ExpandedPillWidthDp, 0.001f)
        assertTrue(CollapsedPillHeightDp < ExpandedPillHeightDp)

        val upperCollapsedTop = computeDockedPillTopDp(
            minTopDp = 44f,
            maxHeightDp = 800f,
            bottomClearanceDp = 84f,
            collapsedHeightDp = CollapsedPillHeightDp,
            currentHeightDp = CollapsedPillHeightDp,
            verticalBias = 0.25f
        )
        val upperExpandedTop = computeDockedPillTopDp(
            minTopDp = 44f,
            maxHeightDp = 800f,
            bottomClearanceDp = 84f,
            collapsedHeightDp = CollapsedPillHeightDp,
            currentHeightDp = ExpandedPillHeightDp,
            verticalBias = 0.25f
        )
        assertEquals(upperCollapsedTop, upperExpandedTop, 0.001f)

        val bottomCollapsedTop = computeDockedPillTopDp(
            minTopDp = 44f,
            maxHeightDp = 800f,
            bottomClearanceDp = 84f,
            collapsedHeightDp = CollapsedPillHeightDp,
            currentHeightDp = CollapsedPillHeightDp,
            verticalBias = 1.0f
        )
        val bottomExpandedTop = computeDockedPillTopDp(
            minTopDp = 44f,
            maxHeightDp = 800f,
            bottomClearanceDp = 84f,
            collapsedHeightDp = CollapsedPillHeightDp,
            currentHeightDp = ExpandedPillHeightDp,
            verticalBias = 1.0f
        )
        assertTrue(bottomExpandedTop < bottomCollapsedTop)
        assertEquals(800f - 84f - ExpandedPillHeightDp, bottomExpandedTop, 0.001f)
    }

    @Test
    fun `portrait nav endpoints match side pill and 4-tab bottom navigation bar`() {
        val maxWidth = 360f
        val maxHeight = 800f
        val sideFrame = computePortraitNavFrame(
            maxWidthDp = maxWidth,
            maxHeightDp = maxHeight,
            sideLeftDp = 10f,
            sideTopDp = 320f,
            sideWidthDp = CollapsedPillWidthDp,
            sideHeightDp = CollapsedPillHeightDp,
            expandedProgress = 0f,
            bottomProgress = 0f
        )
        assertEquals(10f, sideFrame.capsuleLeftDp, 0.001f)
        assertEquals(320f, sideFrame.capsuleTopDp, 0.001f)
        assertEquals(CollapsedPillWidthDp, sideFrame.capsuleWidthDp, 0.001f)
        assertEquals(CollapsedPillHeightDp, sideFrame.capsuleHeightDp, 0.001f)
        assertEquals(0f, sideFrame.terminalAlpha, 0.001f)

        val expandedSideFrame = computePortraitNavFrame(
            maxWidthDp = maxWidth,
            maxHeightDp = maxHeight,
            sideLeftDp = 10f,
            sideTopDp = 240f,
            sideWidthDp = ExpandedPillWidthDp,
            sideHeightDp = ExpandedPillHeightDp,
            expandedProgress = 1f,
            bottomProgress = 0f
        )
        // 竖屏 WebUI 展开后刷新与终端回归同一紧凑全圆胶囊内（64×282dp），不再拆成外置圆钮
        assertEquals(64f, expandedSideFrame.capsuleWidthDp, 0.001f)
        assertEquals(282f, expandedSideFrame.capsuleHeightDp, 0.001f)
        assertEquals(0f, expandedSideFrame.terminalAlpha, 0.001f)

        val bottomFrame = computePortraitNavFrame(
            maxWidthDp = maxWidth,
            maxHeightDp = maxHeight,
            sideLeftDp = maxWidth - ExpandedPillWidthDp - 10f,
            sideTopDp = 240f,
            sideWidthDp = ExpandedPillWidthDp,
            sideHeightDp = ExpandedPillHeightDp,
            expandedProgress = 1f,
            bottomProgress = 1f
        )
        val expectedBottomWidth = computeBottomTabBarWidthDp(maxWidth)
        assertEquals(16f, bottomFrame.capsuleLeftDp, 0.001f)
        assertEquals(maxHeight - 80f, bottomFrame.capsuleTopDp, 0.001f)
        assertEquals(expectedBottomWidth, bottomFrame.capsuleWidthDp, 0.001f)
        assertEquals(64f, bottomFrame.capsuleHeightDp, 0.001f)
        assertEquals(maxWidth - 16f - 64f, bottomFrame.terminalLeftDp, 0.001f)
        assertEquals(maxHeight - 80f, bottomFrame.terminalTopDp, 0.001f)
        assertEquals(1f, bottomFrame.terminalAlpha, 0.001f)
        assertTrue(bottomFrame.tabWidthDp >= 60f)
    }

    @Test
    fun `portrait nav morphs smoothly from both left and right edges without clipping 4 tabs`() {
        val maxWidth = 360f
        val maxHeight = 800f
        val steps = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)

        // 左侧与右侧两条路径分别从 10dp -> 16dp 单调右移、从右缘 -> 16dp 单调左移，并且全过程四格标签严格不越界
        PillDockSide.entries.forEach { side ->
            val sideLeft = if (side == PillDockSide.Right) maxWidth - ExpandedPillWidthDp - 10f else 10f
            val frames = steps.map { progress ->
                computePortraitNavFrame(
                    maxWidthDp = maxWidth,
                    maxHeightDp = maxHeight,
                    sideLeftDp = sideLeft,
                    sideTopDp = 260f,
                    sideWidthDp = ExpandedPillWidthDp,
                    sideHeightDp = ExpandedPillHeightDp,
                    expandedProgress = 1f,
                    bottomProgress = progress
                )
            }

            for (i in 0 until frames.lastIndex) {
                val cur = frames[i]
                val next = frames[i + 1]
                if (side == PillDockSide.Right) {
                    assertTrue(next.capsuleLeftDp < cur.capsuleLeftDp)
                } else {
                    assertTrue(next.capsuleLeftDp > cur.capsuleLeftDp)
                }
                assertTrue(next.capsuleTopDp > cur.capsuleTopDp)
            }

            frames.forEach { frame ->
                assertEquals(4, frame.tabPositionsDp.size)
                frame.tabPositionsDp.forEach { (tabX, tabY) ->
                    assertTrue(tabX >= -0.001f)
                    assertTrue(tabX + frame.tabWidthDp <= frame.capsuleWidthDp + 0.001f)
                    assertTrue(tabY >= -0.001f)
                    assertTrue(tabY + frame.tabHeightDp <= frame.capsuleHeightDp + 0.001f)
                }
            }
        }
    }

    @Test
    fun `landscape rail fits 4 oval tabs with restrained refresh circle and 64dp terminal circle`() {
        val phone360 = computeHomeRailMetrics(360f)
        assertTrue(phone360.compact)
        assertTrue(phone360.minimumTotalHeightDp < 360f)
        assertEquals(48f, phone360.tabHeightDp, 0.001f)
        assertEquals(28f, phone360.outerCornerRadiusDp, 0.001f)
        assertEquals(200f, phone360.tabRailHeightDp, 0.001f)
        assertEquals(40f, phone360.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavUnitSizeDp, phone360.terminalButtonSizeDp, 0.001f)

        val phone320 = computeHomeRailMetrics(320f)
        assertTrue(phone320.compact)
        assertTrue(phone320.minimumTotalHeightDp < 320f)
        assertEquals(44f, phone320.tabHeightDp, 0.001f)
        assertEquals(26f, phone320.outerCornerRadiusDp, 0.001f)
        assertEquals(38f, phone320.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavUnitSizeDp, phone320.terminalButtonSizeDp, 0.001f)

        val tablet600 = computeHomeRailMetrics(600f)
        assertFalse(tablet600.compact)
        assertTrue(tablet600.minimumTotalHeightDp < 600f)
        assertEquals(52f, tablet600.tabHeightDp, 0.001f)
        assertEquals(30f, tablet600.outerCornerRadiusDp, 0.001f)
        assertTrue(HomeNavigationRailBarWidth.value - 8f > tablet600.tabHeightDp)
        assertEquals(44f, tablet600.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavUnitSizeDp, tablet600.terminalButtonSizeDp, 0.001f)
    }

    @Test
    fun `home navigation unifies Capsule indicator shape with 64dp portrait bar and 76dp oval rail`() {
        // 竖屏底栏、竖屏 WebUI 侧边胶囊继续保持 64dp 基准；横屏侧栏加宽至 76dp，使 68dp 内槽呈舒展横向椭圆
        assertEquals(Capsule(), HomeNavBarShape)
        assertEquals(Capsule(), HomeNavIndicatorShape)
        assertEquals(64.dp, PortraitBottomBarHeight)
        assertEquals(76.dp, HomeNavigationRailBarWidth)
        assertEquals(64f, CollapsedPillWidthDp, 0.001f)
        assertEquals(64f, ExpandedPillWidthDp, 0.001f)
        assertEquals(48f, ExpandedTabSlotHeightDp, 0.001f)
        assertEquals(282f, ExpandedPillHeightDp, 0.001f)
    }

    @Test
    fun `portrait tab drag axis only activates in settled bottom bar or expanded webui side pill`() {
        // WebUI 收起态保留整条拖移，不触发页签水滴滑动
        assertEquals(
            PortraitTabDragAxis.None,
            resolvePortraitTabDragAxis(isWebUi = true, expanded = false, expandedProgress = 0f, bottomProgress = 0f)
        )
        // WebUI 展开稳态开放纵向液态滑动
        assertEquals(
            PortraitTabDragAxis.VerticalExpandedSide,
            resolvePortraitTabDragAxis(isWebUi = true, expanded = true, expandedProgress = 1f, bottomProgress = 0f)
        )
        // 切离 WebUI 进入底栏稳态开放横向液态滑动
        assertEquals(
            PortraitTabDragAxis.HorizontalBottom,
            resolvePortraitTabDragAxis(isWebUi = false, expanded = false, expandedProgress = 0f, bottomProgress = 1f)
        )
        // 变形过渡中途禁止双轴抢手势
        assertEquals(
            PortraitTabDragAxis.None,
            resolvePortraitTabDragAxis(isWebUi = true, expanded = true, expandedProgress = 0.5f, bottomProgress = 0.3f)
        )
    }

    @Test
    fun `expanded side pill distinguishes tap collapse from drag switch or drag-back`() {
        assertEquals(
            ExpandedSideDragOutcome.CollapseMenu,
            resolveExpandedSideDragOutcome(targetIndex = HomeTab.WebUi.ordinal, movedDistancePx = 2f, touchSlopPx = 18f)
        )
        assertEquals(
            ExpandedSideDragOutcome.StayExpanded,
            resolveExpandedSideDragOutcome(targetIndex = HomeTab.WebUi.ordinal, movedDistancePx = 42f, touchSlopPx = 18f)
        )
        assertEquals(
            ExpandedSideDragOutcome.SwitchTab,
            resolveExpandedSideDragOutcome(targetIndex = HomeTab.Platforms.ordinal, movedDistancePx = 90f, touchSlopPx = 18f)
        )
    }

    @Test
    fun `liquid accent magnification scales stay bounded to prevent glyph clipping`() {
        assertTrue(HomeNavVerticalAccentScale in 1.05f..1.12f)
        assertTrue(HomeNavHorizontalAccentScale in 1.08f..1.16f)
        assertTrue(HomeNavVerticalAccentScale <= HomeNavHorizontalAccentScale)
    }

    @Test
    fun `clampHomeNavLabelSp preserves normal scale and caps oversized system fontScale`() {
        // 标准系统缩放 1.0 原样保留基准字号
        assertEquals(11f, clampHomeNavLabelSp(baseSp = 11f, systemFontScale = 1.0f), 0.001f)
        // 超大系统字号下抵消多余缩放，确保真实渲染等效字号不超过 baseSp * HomeNavMaxFontScale
        val scaledForLargeFont = clampHomeNavLabelSp(baseSp = 11f, systemFontScale = 1.6f)
        assertEquals(11f * HomeNavMaxFontScale, scaledForLargeFont * 1.6f, 0.001f)
        // 极小字号下保底不低于 0.85x，非正 fontScale 安全兜底
        val scaledForTinyFont = clampHomeNavLabelSp(baseSp = 11f, systemFontScale = 0.7f)
        assertEquals(11f * 0.85f, scaledForTinyFont * 0.7f, 0.001f)
        assertEquals(11f, clampHomeNavLabelSp(baseSp = 11f, systemFontScale = 0f), 0.001f)
    }

    @Test
    fun `all 6 navigation slot geometries keep four-character Chinese labels inside indicator chord`() {
        val scenarios = listOf(
            // 平板横屏椭圆侧栏 68x52
            Triple(68f, 52f, resolveHomeNavTabTypography(narrowVertical = false, compact = false)),
            // 手机横屏椭圆侧栏 68x48
            Triple(68f, 48f, resolveHomeNavTabTypography(narrowVertical = false, compact = true)),
            // 极矮分屏椭圆侧栏 68x44
            Triple(68f, 44f, resolveHomeNavTabTypography(narrowVertical = false, compact = true)),
            // 竖屏 WebUI 展开侧边胶囊 56x48
            Triple(56f, 48f, resolveHomeNavTabTypography(narrowVertical = true, compact = true)),
            // 最窄底栏 59x56（244dp 宽四等分减去内边距）
            Triple(59f, 56f, resolveHomeNavTabTypography(narrowVertical = false, compact = false)),
            // 标准底栏 75x56（308dp 宽四等分减去内边距）
            Triple(75f, 56f, resolveHomeNavTabTypography(narrowVertical = false, compact = false))
        )
        scenarios.forEach { (slotWidthDp, slotHeightDp, typography) ->
            val chordWidthDp = computeCapsuleChordWidthDp(
                widthDp = slotWidthDp,
                heightDp = slotHeightDp,
                offsetFromCenterYDp = typography.totalContentHeightDp * 0.5f
            )
            val maxFourCharLabelWidthDp = 4f * typography.labelSizeSp * HomeNavMaxFontScale
            assertTrue(
                "Slot ${slotWidthDp}x$slotHeightDp chord ($chordWidthDp) should exceed 4-char width ($maxFourCharLabelWidthDp)",
                chordWidthDp > maxFourCharLabelWidthDp
            )
        }
    }
}
