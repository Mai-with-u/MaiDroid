package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
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
}
