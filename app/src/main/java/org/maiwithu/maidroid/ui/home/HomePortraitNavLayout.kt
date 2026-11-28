package org.maiwithu.maidroid.ui.home

import androidx.compose.ui.unit.dp

// 竖屏 WebUI 侧边胶囊与底部导航栏共用的几何尺寸与二维形变插值模型（纯数值逻辑，供渲染层与单测复用）。

internal const val PillInnerInsetDp = 4f
// 竖屏 WebUI 收起态小药丸：统一对齐 56dp 宽（外圆角 28dp），上格「导航」呈 48×48 同心全圆高亮，下格「刷新」48dp，合计高 106dp。
internal const val CollapsedNavButtonHeightDp = 48f
internal const val CollapsedItemGapDp = 2f
internal const val CollapsedRefreshButtonHeightDp = 48f
internal const val CollapsedRefreshTopDp =
    PillInnerInsetDp + CollapsedNavButtonHeightDp + CollapsedItemGapDp

internal const val CollapsedPillWidthDp = HomeNavBarHeightDp
internal const val CollapsedPillHeightDp =
    CollapsedRefreshTopDp + CollapsedRefreshButtonHeightDp + PillInnerInsetDp

/** 竖屏 WebUI 侧边展开态页签槽位高度（38dp）：48×38dp 紧凑横向药丸高亮，压低整条展开高度（234dp）避免过大臃肿。 */
internal const val ExpandedTabSlotHeightDp = 38f

internal val ExpandedActionsTopDp =
    PillInnerInsetDp + ExpandedTabSlotHeightDp * HomeTab.entries.size
internal const val ExpandedDividerSlotHeightDp = 6f
internal const val ExpandedRefreshButtonHeightDp = 32f
// 底部「终端」纯图标槽（36dp）：21dp 图标底边距外框底缘 11.5dp，自然落于 28dp 半圆弧内且不挤压裁切。
internal const val ExpandedTerminalButtonHeightDp = 36f

internal const val ExpandedPillWidthDp = HomeNavBarHeightDp
internal val ExpandedPillHeightDp =
    ExpandedActionsTopDp + ExpandedDividerSlotHeightDp + ExpandedRefreshButtonHeightDp + ExpandedTerminalButtonHeightDp + PillInnerInsetDp

internal val CollapsedPillWidth = CollapsedPillWidthDp.dp
internal val CollapsedPillHeight = CollapsedPillHeightDp.dp
internal val ExpandedPillWidth = ExpandedPillWidthDp.dp
internal val ExpandedPillHeight = ExpandedPillHeightDp.dp

internal val PillOuterPadding = PillInnerInsetDp.dp
internal val CollapsedRefreshButtonHeight = CollapsedRefreshButtonHeightDp.dp
internal val ExpandedDividerSlotHeight = ExpandedDividerSlotHeightDp.dp
internal val ExpandedRefreshButtonHeight = ExpandedRefreshButtonHeightDp.dp
internal val ExpandedTerminalButtonHeight = ExpandedTerminalButtonHeightDp.dp
internal val PillEdgeMargin = 10.dp
internal val PillBottomClearance = 84.dp

/**
 * 竖屏统一导航在任意过渡帧的二维几何描述（单位 dp）：
 * 既描述侧边/底部共用的主液态玻璃胶囊外框及内部 4 颗标签位置，也描述切离 WebUI 进入底栏时独立圆形终端按钮的分离/融入轨迹。
 */
internal data class PortraitNavFrame(
    val capsuleLeftDp: Float,
    val capsuleTopDp: Float,
    val capsuleWidthDp: Float,
    val capsuleHeightDp: Float,
    val tabWidthDp: Float,
    val tabHeightDp: Float,
    val tabPositionsDp: List<Pair<Float, Float>>,
    val terminalLeftDp: Float,
    val terminalTopDp: Float,
    val terminalAlpha: Float,
    val terminalScale: Float
)

/** 限制胶囊垂直停靠比例在 `[0f, 1f]` 安全区内。 */
internal fun clampPillVerticalBias(bias: Float): Float = bias.coerceIn(0f, 1f)

/** 松手后根据胶囊中心横坐标与容器宽度中线比较，决定自动吸附到左侧还是右侧。 */
internal fun resolvePillDockSide(centerX: Float, containerWidth: Float): PillDockSide =
    if (centerX >= containerWidth * 0.5f) PillDockSide.Right else PillDockSide.Left

/** 竖屏导航标签水滴拖拽当前激活的手势方向（仅在底栏或展开侧栏稳态下允许跟手滑动，避免收起拖移和过渡帧抢手势）。 */
internal enum class PortraitTabDragAxis {
    None,
    HorizontalBottom,
    VerticalExpandedSide
}

/**
 * 判断竖屏导航水滴滑块当前应响应横向底栏拖拽还是纵向展开胶囊拖拽：
 * 仅当底栏已落稳（`!isWebUi && bottomProgress > 0.85f`）开放横向拖拽，
 * 或 WebUI 展开胶囊已落稳（`isWebUi && expanded && expandedProgress > 0.85f && bottomProgress < 0.15f`）开放纵向拖拽。
 */
internal fun resolvePortraitTabDragAxis(
    isWebUi: Boolean,
    expanded: Boolean,
    expandedProgress: Float,
    bottomProgress: Float
): PortraitTabDragAxis = when {
    !isWebUi && bottomProgress > 0.85f -> PortraitTabDragAxis.HorizontalBottom
    isWebUi && expanded && expandedProgress > 0.85f && bottomProgress < 0.15f ->
        PortraitTabDragAxis.VerticalExpandedSide
    else -> PortraitTabDragAxis.None
}

/** 竖屏 WebUI 展开态下，按住首格水滴松手后的交互意图分类。 */
internal enum class ExpandedSideDragOutcome {
    CollapseMenu,
    SwitchTab,
    StayExpanded
}

/**
 * 判定竖屏 WebUI 展开胶囊内纵向水滴滑块松手后的动作：
 * - 落到其余三格（消息平台/扩展组件/设置）时直接切换对应标签；
 * - 仍落在首格 WebUI 且总滑动距离小于触摸阈值时视为轻点收起菜单；
 * - 拖出去又拖回首格松手则保留展开态并把水滴弹回首格。
 */
internal fun resolveExpandedSideDragOutcome(
    targetIndex: Int,
    movedDistancePx: Float,
    touchSlopPx: Float
): ExpandedSideDragOutcome = when {
    targetIndex != HomeTab.WebUi.ordinal -> ExpandedSideDragOutcome.SwitchTab
    movedDistancePx < touchSlopPx -> ExpandedSideDragOutcome.CollapseMenu
    else -> ExpandedSideDragOutcome.StayExpanded
}

/**
 * 计算停靠时的顶部纵坐标（dp）：
 * 先按收起态可用垂直区间求出用户停靠基线；展开时在中上部直接向下原地伸展、顶部稳固不动，
 * 仅当停靠在最底部接近网页底栏时自动向上退让，避免被裁切或遮挡网页底部菜单。
 */
internal fun computeDockedPillTopDp(
    minTopDp: Float,
    maxHeightDp: Float,
    bottomClearanceDp: Float,
    collapsedHeightDp: Float,
    currentHeightDp: Float,
    verticalBias: Float
): Float {
    val clampedBias = clampPillVerticalBias(verticalBias)
    val maxTopCollapsed = (maxHeightDp - collapsedHeightDp - bottomClearanceDp).coerceAtLeast(minTopDp)
    val collapsedTop = minTopDp + (maxTopCollapsed - minTopDp) * clampedBias
    val maxTopCurrent = (maxHeightDp - currentHeightDp - bottomClearanceDp).coerceAtLeast(minTopDp)
    return collapsedTop.coerceIn(minTopDp, maxTopCurrent)
}

/**
 * 计算侧边胶囊（左或右侧停靠）与底部导航栏之间的连续二维形变插值几何：
 * - [bottomProgress] 为 0 时处于侧边单一胶囊态（展开时刷新与终端仍留在主胶囊内部），
 *   为 1 时精确对齐底部四标签 + 右下独立终端按钮布局；
 * - 位移使用未夹紧的 [bottomProgress] 保留阻尼弹簧缓冲，外框尺寸与透明度按 [0, 1] 夹紧严防越界。
 */
internal fun computePortraitNavFrame(
    maxWidthDp: Float,
    maxHeightDp: Float,
    sideLeftDp: Float,
    sideTopDp: Float,
    sideWidthDp: Float,
    sideHeightDp: Float,
    expandedProgress: Float,
    bottomProgress: Float
): PortraitNavFrame {
    val clampedExpanded = expandedProgress.coerceIn(0f, 1f)
    val clampedBottom = bottomProgress.coerceIn(0f, 1f)

    val bottomTabBarWidth = computeBottomTabBarWidthDp(maxWidthDp)
    val bottomBarHeight = PortraitBottomBarHeight.value
    val bottomBarTop = (maxHeightDp - PortraitBottomBarBottomPadding.value - bottomBarHeight).coerceAtLeast(0f)
    val bottomBarLeft = PortraitBottomBarHorizontalPadding.value
    val bottomSingleTabWidth = (bottomTabBarWidth - PillInnerInsetDp * 2f) / HomeTab.entries.size
    val bottomTerminalLeft = maxWidthDp - PortraitBottomBarHorizontalPadding.value - bottomBarHeight

    // 主胶囊位置保留微幅弹簧缓冲；宽高严守 [0, 1] 防止过冲缩出负数尺寸
    val capsuleLeft = lerpFloat(sideLeftDp, bottomBarLeft, bottomProgress)
    val capsuleTop = lerpFloat(sideTopDp, bottomBarTop, bottomProgress)
    val capsuleWidth = lerpFloat(sideWidthDp, bottomTabBarWidth, clampedBottom)
    val capsuleHeight = lerpFloat(sideHeightDp, bottomBarHeight, clampedBottom)

    val tabWidth = lerpFloat(sideWidthDp - PillInnerInsetDp * 2f, bottomSingleTabWidth, clampedBottom)
    val tabHeight = lerpFloat(ExpandedTabSlotHeightDp, HomeTabButtonHeightDp, clampedBottom)
    val tabPositions = HomeTab.entries.indices.map { index ->
        val sideSlotY = PillInnerInsetDp + (ExpandedTabSlotHeightDp * index) * clampedExpanded
        val tabX = lerpFloat(PillInnerInsetDp, PillInnerInsetDp + bottomSingleTabWidth * index, clampedBottom)
        val tabY = lerpFloat(sideSlotY, PillInnerInsetDp, clampedBottom)
        tabX to tabY
    }

    val sideTerminalAnchorLeft = sideLeftDp + (sideWidthDp - bottomBarHeight) * 0.5f
    val sideTerminalAnchorTop = sideTopDp + sideHeightDp - bottomBarHeight
    val terminalLeft = lerpFloat(sideTerminalAnchorLeft, bottomTerminalLeft, bottomProgress)
    val terminalTop = lerpFloat(sideTerminalAnchorTop, bottomBarTop, bottomProgress)
    val terminalAlpha = ((clampedBottom - 0.08f) / 0.72f).coerceIn(0f, 1f)
    val terminalScale = 0.82f + 0.18f * clampedBottom

    return PortraitNavFrame(
        capsuleLeftDp = capsuleLeft,
        capsuleTopDp = capsuleTop,
        capsuleWidthDp = capsuleWidth,
        capsuleHeightDp = capsuleHeight,
        tabWidthDp = tabWidth,
        tabHeightDp = tabHeight,
        tabPositionsDp = tabPositions,
        terminalLeftDp = terminalLeft,
        terminalTopDp = terminalTop,
        terminalAlpha = terminalAlpha,
        terminalScale = terminalScale
    )
}

internal fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
