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
            currentHeightDp = ExpandedSideGroupTotalHeightDp,
            verticalBias = 1.0f
        )
        assertTrue(bottomExpandedTop < bottomCollapsedTop)
        assertEquals(800f - 84f - ExpandedSideGroupTotalHeightDp, bottomExpandedTop, 0.001f)
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
        assertEquals(0f, sideFrame.refreshAlpha, 0.001f)
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
        // 竖屏 WebUI 展开后主胶囊精简为 64×208 紧凑四格全圆椭圆，下方居中悬停 40dp 刷新小圆与 64dp 终端正圆
        assertEquals(64f, expandedSideFrame.capsuleWidthDp, 0.001f)
        assertEquals(208f, expandedSideFrame.capsuleHeightDp, 0.001f)
        assertEquals(computeHomeRailMetrics(360f).tabRailHeightDp, expandedSideFrame.capsuleHeightDp, 0.001f)
        assertEquals(1f, expandedSideFrame.refreshAlpha, 0.001f)
        assertEquals(10f + (64f - ExpandedSideRefreshSizeDp) * 0.5f, expandedSideFrame.refreshLeftDp, 0.001f)
        assertEquals(240f + 208f + ExpandedSideRefreshGapDp, expandedSideFrame.refreshTopDp, 0.001f)
        assertEquals(1f, expandedSideFrame.terminalAlpha, 0.001f)
        assertEquals(10f, expandedSideFrame.terminalLeftDp, 0.001f)
        assertEquals(
            240f + 208f + ExpandedSideRefreshGapDp + ExpandedSideRefreshSizeDp + ExpandedSideTerminalGapDp,
            expandedSideFrame.terminalTopDp,
            0.001f
        )

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
        assertEquals(0f, bottomFrame.refreshAlpha, 0.001f)
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
    fun `landscape rail fits 4 tabs with restrained refresh circle and 64dp terminal circle`() {
        val phone360 = computeHomeRailMetrics(360f)
        assertTrue(phone360.compact)
        assertTrue(phone360.minimumTotalHeightDp < 360f)
        assertEquals(ExpandedPillHeightDp, phone360.tabRailHeightDp, 0.001f)
        assertEquals(40f, phone360.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavUnitSizeDp, phone360.terminalButtonSizeDp, 0.001f)

        val phone320 = computeHomeRailMetrics(320f)
        assertTrue(phone320.compact)
        assertTrue(phone320.minimumTotalHeightDp < 320f)
        assertEquals(38f, phone320.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavUnitSizeDp, phone320.terminalButtonSizeDp, 0.001f)

        val tablet600 = computeHomeRailMetrics(600f)
        assertFalse(tablet600.compact)
        assertTrue(tablet600.minimumTotalHeightDp < 600f)
        assertEquals(HomeTabButtonHeightDp, tablet600.tabHeightDp, 0.001f)
        assertEquals(44f, tablet600.refreshButtonSizeDp, 0.001f)
        assertEquals(HomeNavUnitSizeDp, tablet600.terminalButtonSizeDp, 0.001f)
    }

    @Test
    fun `home navigation unifies full-circle Capsule shape and 64dp base thickness across modes`() {
        // 竖屏底栏、竖屏 WebUI 侧边胶囊与横屏导航竖栏统一使用全圆 Capsule()，短边 64dp -> 32dp 外半圆角、槽位 50~56dp 同心圆
        assertEquals(Capsule(), HomeNavBarShape)
        assertEquals(Capsule(), HomeNavIndicatorShape)
        assertEquals(64.dp, PortraitBottomBarHeight)
        assertEquals(64.dp, HomeNavigationRailBarWidth)
        assertEquals(64f, CollapsedPillWidthDp, 0.001f)
        assertEquals(64f, ExpandedPillWidthDp, 0.001f)
        assertEquals(50f, ExpandedTabSlotHeightDp, 0.001f)
        assertEquals(208f, ExpandedPillHeightDp, 0.001f)
    }
}
