package org.maiwithu.maidroid.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BounceOverscrollTest {

    @Test
    fun `initial rubber band pull follows base damping ratio`() {
        val next = computeRubberBandStep(
            currentOffsetPx = 0f,
            deltaPx = 100f,
            maxOffsetPx = 500f
        )
        assertEquals(56f, next, 0.01f)
    }

    @Test
    fun `rubber band resists stronger near limit and never exceeds max offset`() {
        val nearLimit = computeRubberBandStep(
            currentOffsetPx = 450f,
            deltaPx = 500f,
            maxOffsetPx = 500f
        )
        assertTrue(nearLimit > 450f)
        assertTrue(nearLimit <= 500f)

        val capped = computeRubberBandStep(
            currentOffsetPx = 500f,
            deltaPx = 200f,
            maxOffsetPx = 500f
        )
        assertEquals(500f, capped, 0.001f)
    }

    @Test
    fun `rubber band behaves symmetrically for bottom over-pull`() {
        val positive = computeRubberBandStep(
            currentOffsetPx = 120f,
            deltaPx = 80f,
            maxOffsetPx = 400f
        )
        val negative = computeRubberBandStep(
            currentOffsetPx = -120f,
            deltaPx = -80f,
            maxOffsetPx = 400f
        )
        assertEquals(-positive, negative, 0.001f)
    }

    @Test
    fun `applyToScroll accumulates top overscroll and pulls back one to one before scrolling content`() {
        val effect = BounceOverscrollEffect(maxOverscrollPx = 500f, splitTopHeader = true)

        val consumedPull = effect.applyToScroll(
            delta = Offset(0f, 100f),
            source = NestedScrollSource.UserInput,
            performScroll = { Offset.Zero }
        )
        assertEquals(Offset(0f, 100f), consumedPull)
        assertEquals(56f, effect.overscrollY, 0.01f)
        assertEquals(56f, effect.topOverscrollPx, 0.01f)
        assertTrue(effect.isInProgress)

        var forwardedToList = Offset.Zero
        val consumedPushBack = effect.applyToScroll(
            delta = Offset(0f, -80f),
            source = NestedScrollSource.UserInput,
            performScroll = { remaining ->
                forwardedToList = remaining
                remaining
            }
        )
        assertEquals(0f, effect.overscrollY, 0.001f)
        assertEquals(-24f, forwardedToList.y, 0.01f)
        assertEquals(-80f, consumedPushBack.y, 0.01f)
        assertFalse(effect.isInProgress)
    }

    @Test
    fun `applyToScroll ignores non user input leftover deltas`() {
        val effect = BounceOverscrollEffect(maxOverscrollPx = 400f)

        val consumed = effect.applyToScroll(
            delta = Offset(0f, 120f),
            source = NestedScrollSource.SideEffect,
            performScroll = { Offset.Zero }
        )

        assertEquals(Offset.Zero, consumed)
        assertEquals(0f, effect.overscrollY, 0.001f)
        assertFalse(effect.isInProgress)
    }
}
