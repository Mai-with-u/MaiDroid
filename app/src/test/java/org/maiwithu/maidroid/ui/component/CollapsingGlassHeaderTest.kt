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
    fun `title and action shrink continuously while scrim rises and subtitle fades early`() {
        // 顶部静止时毛玻璃透明、副标题可见、标题与动作按钮保持 1.0 倍展开尺寸
        assertEquals(0f, computeGlassBarScrimAlpha(0f), 0.001f)
        assertEquals(1f, computeHeaderSubtitleAlpha(0f), 0.001f)
        assertEquals(1f, computeCollapsingTitleScale(0f, expandedSp = 30f, collapsedSp = 19f), 0.001f)
        assertEquals(1f, computeCollapsingActionScale(0f), 0.001f)
        assertEquals(100f, computeCollapsingHeaderOffsetY(0f, expandedTopPx = 100f, collapsedTopPx = 10f), 0.001f)
        assertEquals(
            120f,
            computeCollapsingHeaderOffsetY(0f, expandedTopPx = 100f, collapsedTopPx = 10f, headerOverscrollPx = 20f),
            0.001f
        )

        // 中途阶段标题与按钮按比例平滑缩小并上移，副标题提前大幅淡出避免相撞
        val midTitleScale = computeCollapsingTitleScale(0.5f, expandedSp = 30f, collapsedSp = 19f)
        assertEquals(0.5f * (1f + 19f / 30f), midTitleScale, 0.001f)
        assertTrue(computeCollapsingActionScale(0.5f) in 0.94f..0.96f)
        assertEquals(55f, computeCollapsingHeaderOffsetY(0.5f, expandedTopPx = 100f, collapsedTopPx = 10f), 0.001f)
        assertTrue(computeGlassBarScrimAlpha(0.25f) > 0.3f)
        assertEquals(0f, computeHeaderSubtitleAlpha(0.5f), 0.001f)

        // 完全吸顶后锁定在紧凑字号、缩放与目标纵向高度，且不受视差余量影响
        assertEquals(1f, computeGlassBarScrimAlpha(1f), 0.001f)
        assertEquals(19f / 30f, computeCollapsingTitleScale(1f, expandedSp = 30f, collapsedSp = 19f), 0.001f)
        assertEquals(38f / 42f, computeCollapsingActionScale(1f), 0.001f)
        assertEquals(
            10f,
            computeCollapsingHeaderOffsetY(1f, expandedTopPx = 100f, collapsedTopPx = 10f, headerOverscrollPx = 20f),
            0.001f
        )
    }
}
