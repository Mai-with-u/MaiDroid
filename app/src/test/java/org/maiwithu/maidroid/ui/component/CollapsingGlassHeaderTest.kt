package org.maiwithu.maidroid.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapsingGlassHeaderTest {

    @Test
    fun `lazy list collapse progress scales with first item offset and locks after item zero`() {
        assertEquals(0f, computeLazyHeaderCollapseProgress(0, 0, 120f), 0.001f)
        assertEquals(0.5f, computeLazyHeaderCollapseProgress(0, 60, 120f), 0.001f)
        assertEquals(1f, computeLazyHeaderCollapseProgress(0, 200, 120f), 0.001f)
        assertEquals(1f, computeLazyHeaderCollapseProgress(1, 0, 120f), 0.001f)
        assertEquals(0f, computeLazyHeaderCollapseProgress(0, -10, 120f), 0.001f)
    }

    @Test
    fun `scroll container collapse progress clamps smoothly within distance`() {
        assertEquals(0f, computeScrollHeaderCollapseProgress(0, 100f), 0.001f)
        assertEquals(0.4f, computeScrollHeaderCollapseProgress(40, 100f), 0.001f)
        assertEquals(1f, computeScrollHeaderCollapseProgress(180, 100f), 0.001f)
        assertEquals(0f, computeScrollHeaderCollapseProgress(50, 0f), 0.001f)
    }

    @Test
    fun `glass scrim appears before pinned title and large header fades out cleanly`() {
        // 顶部静止时所有遮罩和小标题全透明，大标题完全可见
        assertEquals(0f, computeGlassBarScrimAlpha(0f), 0.001f)
        assertEquals(0f, computePinnedTitleReveal(0f), 0.001f)
        assertEquals(1f, computeLargeHeaderAlpha(0f), 0.001f)

        // 刚向上滚动到 0.25 时毛玻璃渐变已开始显现，小标题尚未冒头避免与大标题同时存在
        assertTrue(computeGlassBarScrimAlpha(0.25f) > 0.3f)
        assertEquals(0f, computePinnedTitleReveal(0.25f), 0.001f)
        assertTrue(computeLargeHeaderAlpha(0.25f) in 0.5f..0.7f)

        // 过半阶段大小标题顺利交接，大标题提前彻底隐去
        assertEquals(1f, computeGlassBarScrimAlpha(0.75f), 0.001f)
        assertTrue(computePinnedTitleReveal(0.75f) > 0.5f)
        assertEquals(0f, computeLargeHeaderAlpha(0.75f), 0.001f)

        // 完全吸顶
        assertEquals(1f, computeGlassBarScrimAlpha(1f), 0.001f)
        assertEquals(1f, computePinnedTitleReveal(1f), 0.001f)
        assertEquals(0f, computeLargeHeaderAlpha(1f), 0.001f)
    }
}
