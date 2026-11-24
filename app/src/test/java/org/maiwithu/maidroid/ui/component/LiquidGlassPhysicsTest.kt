package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidGlassPhysicsTest {

    @Test
    fun `button drag transform stays identity at rest and within bounded jelly stretch`() {
        val rest = computeLiquidButtonDragTransform(
            widthPx = 240f,
            heightPx = 88f,
            maxDragHeightPx = 8f,
            pressProgress = 0f,
            offsetX = 0f,
            offsetY = 0f
        )
        assertEquals(0f, rest.translationXPx, 0.001f)
        assertEquals(0f, rest.translationYPx, 0.001f)
        assertEquals(1f, rest.scaleX, 0.001f)
        assertEquals(1f, rest.scaleY, 0.001f)

        val pressed = computeLiquidButtonDragTransform(
            widthPx = 240f,
            heightPx = 88f,
            maxDragHeightPx = 8f,
            pressProgress = 1f,
            offsetX = 120f,
            offsetY = -40f
        )
        assertTrue(pressed.translationXPx > 0f && pressed.translationXPx < 88f)
        assertTrue(pressed.translationYPx < 0f && pressed.translationYPx > -88f)
        assertTrue(pressed.scaleX > 1.09f)
        assertTrue(pressed.scaleY > 1.09f)
    }

    @Test
    fun `button drag transform guards zero dimensions safely`() {
        val degenerate = computeLiquidButtonDragTransform(
            widthPx = 0f,
            heightPx = 0f,
            maxDragHeightPx = 8f,
            pressProgress = 1f,
            offsetX = 50f,
            offsetY = 50f
        )
        assertEquals(1f, degenerate.scaleX, 0.001f)
        assertEquals(1f, degenerate.scaleY, 0.001f)
    }

    @Test
    fun `panel offset preserves drag direction and clamps at max travel`() {
        assertEquals(0f, computeLiquidPanelOffsetPx(0f, 300f, 8f), 0.001f)
        val right = computeLiquidPanelOffsetPx(150f, 300f, 8f)
        val clampedRight = computeLiquidPanelOffsetPx(900f, 300f, 8f)
        val clampedLeft = computeLiquidPanelOffsetPx(-900f, 300f, 8f)

        assertTrue(right > 0f && right < 8f)
        assertEquals(8f, clampedRight, 0.001f)
        assertEquals(-8f, clampedLeft, 0.001f)
    }

    @Test
    fun `toggle drag outcome flips on tap, settles by midpoint on drag, and reverts on cancel`() {
        // 轻点反相
        assertTrue(resolveToggleDragOutcome(wasDragged = false, cancelled = false, targetFraction = 0f, currentlyChecked = false))
        assertFalse(resolveToggleDragOutcome(wasDragged = false, cancelled = false, targetFraction = 1f, currentlyChecked = true))

        // 跟手拖拽过半结算
        assertTrue(resolveToggleDragOutcome(wasDragged = true, cancelled = false, targetFraction = 0.55f, currentlyChecked = false))
        assertFalse(resolveToggleDragOutcome(wasDragged = true, cancelled = false, targetFraction = 0.45f, currentlyChecked = true))

        // 被列表纵向滚动打断 cancel 时严格保持原态不误触发
        assertFalse(resolveToggleDragOutcome(wasDragged = true, cancelled = true, targetFraction = 0.9f, currentlyChecked = false))
        assertTrue(resolveToggleDragOutcome(wasDragged = true, cancelled = true, targetFraction = 0.1f, currentlyChecked = true))
    }

    @Test
    fun `toggle horizontal drag lock ignores jitter and vertical scroll while locking clear horizontal swipes`() {
        // 未超过防抖阈值的微抖不锁定横向拖拽，防止轻点开关被误判成拖动
        assertFalse(shouldLockToggleHorizontalDrag(netDeltaX = 4f, netDeltaY = 1f, lockSlopPx = 6f))
        assertFalse(shouldLockToggleHorizontalDrag(netDeltaX = 6f, netDeltaY = 0f, lockSlopPx = 6f))

        // 正负两个方向的清晰横划且横向位移严格大于纵向位移时锁定开关拖拽
        assertTrue(shouldLockToggleHorizontalDrag(netDeltaX = 8f, netDeltaY = 2f, lockSlopPx = 6f))
        assertTrue(shouldLockToggleHorizontalDrag(netDeltaX = -9f, netDeltaY = -3f, lockSlopPx = 6f))

        // 纵向滚动主导或 45 度斜滑保持不锁定，把垂直滑动权让给外层列表
        assertFalse(shouldLockToggleHorizontalDrag(netDeltaX = 10f, netDeltaY = 14f, lockSlopPx = 6f))
        assertFalse(shouldLockToggleHorizontalDrag(netDeltaX = 10f, netDeltaY = -10f, lockSlopPx = 6f))

        // 零或负阈值兜底不越界，只有真实横向主导位移才返回 true
        assertFalse(shouldLockToggleHorizontalDrag(netDeltaX = 0f, netDeltaY = 0f, lockSlopPx = -2f))
        assertTrue(shouldLockToggleHorizontalDrag(netDeltaX = 2f, netDeltaY = 1f, lockSlopPx = -2f))
    }
}
