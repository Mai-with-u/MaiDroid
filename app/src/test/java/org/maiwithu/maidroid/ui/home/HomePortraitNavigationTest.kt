package org.maiwithu.maidroid.ui.home

import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

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
    fun `collapsed pill keeps unified 56dp width with full-circle top slot and shifts up only near bottom`() {
        assertEquals(HomeNavBarHeightDp, CollapsedPillWidthDp, 0.001f)
        assertEquals(106f, CollapsedPillHeightDp, 0.001f)
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
        // 竖屏 WebUI 展开后刷新与终端回归同一紧凑全圆胶囊内（56×234dp），内层页签高亮呈 48×38 紧凑药丸
        assertEquals(56f, expandedSideFrame.capsuleWidthDp, 0.001f)
        assertEquals(234f, expandedSideFrame.capsuleHeightDp, 0.001f)
        assertEquals(48f, expandedSideFrame.tabWidthDp, 0.001f)
        assertEquals(38f, expandedSideFrame.tabHeightDp, 0.001f)
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
        assertEquals(maxHeight - 72f, bottomFrame.capsuleTopDp, 0.001f)
        assertEquals(expectedBottomWidth, bottomFrame.capsuleWidthDp, 0.001f)
        assertEquals(56f, bottomFrame.capsuleHeightDp, 0.001f)
        assertEquals(maxWidth - 16f - 56f, bottomFrame.terminalLeftDp, 0.001f)
        assertEquals(maxHeight - 72f, bottomFrame.terminalTopDp, 0.001f)
        assertEquals(1f, bottomFrame.terminalAlpha, 0.001f)
        assertTrue(bottomFrame.tabWidthDp >= 62f)
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
    fun `landscape rail fits 4 oval tabs with restrained refresh circle and 56dp terminal circle`() {
        val phone360 = computeHomeRailMetrics(360f)
        assertTrue(phone360.compact)
        assertTrue(phone360.minimumTotalHeightDp < 360f)
        assertEquals(46f, phone360.tabHeightDp, 0.001f)
        assertEquals(196f, phone360.tabRailHeightDp, 0.001f)
        assertEquals(40f, phone360.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavBarHeightDp, phone360.terminalButtonSizeDp, 0.001f)

        val phone320 = computeHomeRailMetrics(320f)
        assertTrue(phone320.compact)
        assertTrue(phone320.minimumTotalHeightDp < 320f)
        assertEquals(44f, phone320.tabHeightDp, 0.001f)
        assertEquals(188f, phone320.tabRailHeightDp, 0.001f)
        assertEquals(38f, phone320.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavBarHeightDp, phone320.terminalButtonSizeDp, 0.001f)

        val tablet600 = computeHomeRailMetrics(600f)
        assertFalse(tablet600.compact)
        assertTrue(tablet600.minimumTotalHeightDp < 600f)
        assertEquals(HomeTabButtonHeightDp, tablet600.tabHeightDp, 0.001f)
        assertEquals(204f, tablet600.tabRailHeightDp, 0.001f)
        assertTrue(HomeNavigationRailBarWidth.value - HomeRailHorizontalInsetDp * 2f > tablet600.tabHeightDp)
        assertEquals(44f, tablet600.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavBarHeightDp, tablet600.terminalButtonSizeDp, 0.001f)
    }

    @Test
    fun `home navigation unifies Capsule bar and indicator shapes with 56dp portrait bar and 72dp oval rail`() {
        // 竖屏底栏与竖屏 WebUI 侧边胶囊统一锁定 56dp 短边（收起高亮 48×48 全圆、展开高亮 48×38 紧凑药丸），横屏侧栏使用 72dp 纯全圆药丸 + 64×48/46/44dp 内椭圆
        assertEquals(Capsule(), HomeNavBarShape)
        assertEquals(Capsule(), HomeNavIndicatorShape)
        assertEquals(56.dp, PortraitBottomBarHeight)
        assertEquals(48f, HomeTabButtonHeightDp, 0.001f)
        assertEquals(72.dp, HomeNavigationRailBarWidth)
        assertEquals(56f, CollapsedPillWidthDp, 0.001f)
        assertEquals(56f, ExpandedPillWidthDp, 0.001f)
        assertEquals(38f, ExpandedTabSlotHeightDp, 0.001f)
        assertEquals(234f, ExpandedPillHeightDp, 0.001f)

        // 验证竖屏 WebUI 展开态 48x38 紧凑内药丸在 56dp 外全圆半圆弧内留有 1.5dp+ 斜角余量
        val sideOuterRadiusDp = ExpandedPillWidthDp * 0.5f
        val sideInnerRadiusDp = ExpandedTabSlotHeightDp * 0.5f
        val sideCenterOffsetDp = sideOuterRadiusDp - (4f + sideInnerRadiusDp)
        val sideCornerDistanceDp = sideInnerRadiusDp + hypot(sideCenterOffsetDp, sideCenterOffsetDp)
        assertTrue(
            "48x38 expanded pill corner ($sideCornerDistanceDp) must keep > 1.5dp clearance inside 56dp outer radius ($sideOuterRadiusDp)",
            sideOuterRadiusDp - sideCornerDistanceDp > 1.5f
        )

        // 验证三档内层横向椭圆（64x48 / 64x46 / 64x44）搭配左右 4dp、上下 6dp 内边距放入 72dp 外层全圆药丸半圆弧内时留有 1dp+ 斜角余量
        val outerRadiusDp = HomeNavigationRailBarWidthDp * 0.5f
        val innerWidthDp = HomeNavigationRailBarWidthDp - HomeRailHorizontalInsetDp * 2f
        listOf(48f, 46f, 44f).forEach { innerHeightDp ->
            val innerRadiusDp = innerHeightDp * 0.5f
            val centerOffsetXDp = (innerWidthDp - innerHeightDp) * 0.5f
            val centerOffsetYDp = (outerRadiusDp - HomeRailVerticalInsetDp - innerRadiusDp).coerceAtLeast(0f)
            val cornerDistanceDp = innerRadiusDp + hypot(centerOffsetXDp, centerOffsetYDp)
            assertTrue(
                "64x$innerHeightDp inner oval corner ($cornerDistanceDp) must keep > 1dp clearance inside 72dp outer capsule radius ($outerRadiusDp)",
                outerRadiusDp - cornerDistanceDp > 1f
            )
        }
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
        val narrowPhoneBottomSlotWidth = (computeBottomTabBarWidthDp(359f) - 8f) / HomeTab.entries.size
        val standardBottomSlotWidth = (computeBottomTabBarWidthDp(412f) - 8f) / HomeTab.entries.size
        val scenarios = listOf(
            // 平板横屏椭圆侧栏 64x48
            Triple(64f, 48f, resolveHomeNavTabTypography(narrowVertical = false, compact = false)),
            // 手机横屏椭圆侧栏 64x46
            Triple(64f, 46f, resolveHomeNavTabTypography(narrowVertical = false, compact = true)),
            // 极矮分屏椭圆侧栏 64x44
            Triple(64f, 44f, resolveHomeNavTabTypography(narrowVertical = false, compact = true)),
            // 竖屏 WebUI 展开侧边胶囊 48x38 紧凑药丸
            Triple(48f, 38f, resolveHomeNavTabTypography(narrowVertical = true, compact = true)),
            // 窄屏手机底栏（359dp 屏宽 -> 62.75x48）
            Triple(narrowPhoneBottomSlotWidth, 48f, resolveHomeNavTabTypography(narrowVertical = false, compact = false)),
            // 标准宽底栏（308dp 宽四等分 -> 75x48）
            Triple(standardBottomSlotWidth, 48f, resolveHomeNavTabTypography(narrowVertical = false, compact = false))
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
