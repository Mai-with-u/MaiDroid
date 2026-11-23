package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidSegmentedSelectorTest {
    @Test
    fun `drag target clamps to valid segment bounds`() {
        assertEquals(0, resolveSegmentDragTarget(targetValue = -0.8f, itemCount = 2))
        assertEquals(0, resolveSegmentDragTarget(targetValue = 0.4f, itemCount = 2))
        assertEquals(1, resolveSegmentDragTarget(targetValue = 0.6f, itemCount = 2))
        assertEquals(1, resolveSegmentDragTarget(targetValue = 3.2f, itemCount = 2))

        assertEquals(0, resolveSegmentDragTarget(targetValue = 0.2f, itemCount = 3))
        assertEquals(1, resolveSegmentDragTarget(targetValue = 1.1f, itemCount = 3))
        assertEquals(2, resolveSegmentDragTarget(targetValue = 1.9f, itemCount = 3))
        assertEquals(0, resolveSegmentDragTarget(targetValue = 5f, itemCount = 1))
    }
}
