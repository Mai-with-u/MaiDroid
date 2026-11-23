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
}
