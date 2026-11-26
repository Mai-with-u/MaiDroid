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

    @Test
    fun `liquid slider thumb offset and position mapping align cleanly across range and direction`() {
        // 滑块在 0%、50%、100% 处严丝合缝停留在 [0, trackWidth - thumbWidth]，不会冲出两端
        assertEquals(0f, computeLiquidSliderThumbOffsetPx(0f, trackWidthPx = 300f, thumbWidthPx = 40f), 0.001f)
        assertEquals(130f, computeLiquidSliderThumbOffsetPx(0.5f, trackWidthPx = 300f, thumbWidthPx = 40f), 0.001f)
        assertEquals(260f, computeLiquidSliderThumbOffsetPx(1f, trackWidthPx = 300f, thumbWidthPx = 40f), 0.001f)
        assertEquals(-260f, computeLiquidSliderThumbOffsetPx(1f, trackWidthPx = 300f, thumbWidthPx = 40f, isLtr = false), 0.001f)

        val range = 0.60f..1.30f
        // 按中心行程映射点击位置，并夹紧越界坐标与倒序方向
        assertEquals(0.60f, computeLiquidSliderValueForPosition(0f, 300f, 40f, range), 0.0001f)
        assertEquals(0.60f, computeLiquidSliderValueForPosition(20f, 300f, 40f, range), 0.0001f)
        assertEquals(0.95f, computeLiquidSliderValueForPosition(150f, 300f, 40f, range), 0.0001f)
        assertEquals(1.30f, computeLiquidSliderValueForPosition(280f, 300f, 40f, range), 0.0001f)
        assertEquals(1.30f, computeLiquidSliderValueForPosition(350f, 300f, 40f, range), 0.0001f)
        assertEquals(0.95f, computeLiquidSliderValueForPosition(150f, 300f, 40f, range, isLtr = false), 0.0001f)
    }

    @Test
    fun `liquid slider haptic tick triggers on 5 percent crossings while resisting jitter`() {
        val range = 0.60f..1.30f
        val step = 0.05f
        // 初始位于 1.00f（第 8 个 5% 刻度）
        val startTick = resolveLiquidSliderHapticTick(-1, 1.00f, range, step)
        assertEquals(8, startTick)

        // 中点附近微幅抖动（1.026f）不触发跳格震动，跨越中点 + 滞回死区后才进第 9 格
        assertEquals(8, resolveLiquidSliderHapticTick(startTick, 1.026f, range, step))
        assertEquals(9, resolveLiquidSliderHapticTick(startTick, 1.030f, range, step))

        // 快速跨过多格到 1.25f（第 13 格）一步落位
        assertEquals(13, resolveLiquidSliderHapticTick(9, 1.25f, range, step))
        assertEquals(0, resolveLiquidSliderHapticTick(0, 1.00f, range, tickStep = 0f))
    }

    @Test
    fun `liquid slider haptic tick supports dense 2 percent steps cleanly`() {
        val range = 0.60f..1.30f
        val denseStep = 0.02f
        // 60%..130% 共 35 档，默认 1.00f 恰好在第 20 档
        val tick100 = resolveLiquidSliderHapticTick(-1, 1.00f, range, denseStep)
        assertEquals(20, tick100)
        // 跨过半格 + 滞回死区即触发第 21 档（约 +0.0112f 以上）
        assertEquals(20, resolveLiquidSliderHapticTick(tick100, 1.010f, range, denseStep))
        assertEquals(21, resolveLiquidSliderHapticTick(tick100, 1.013f, range, denseStep))
    }
}
