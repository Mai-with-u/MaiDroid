package org.maiwithu.maidroid.ui.home

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.ui.component.DampedDragAnimation
import org.maiwithu.maidroid.ui.component.InteractiveHighlight
import org.maiwithu.maidroid.ui.component.computeLiquidPanelOffsetPx
import org.maiwithu.maidroid.ui.component.liquidGlassSurface
import org.maiwithu.maidroid.ui.component.rememberLiquidHaptics
import org.maiwithu.maidroid.ui.component.resolveLiquidHapticSlot
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.GlassFallbackSurface
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.LiquidBarSurface
import org.maiwithu.maidroid.ui.theme.TextPrimary
import kotlin.math.abs
import kotlin.math.roundToInt

private const val PillIdleFadeDelayMs = 3_000L

private const val PillInnerInsetDp = 4f
// 竖屏 WebUI 收起态小药丸：统一对齐 56dp 宽（外圆角 28dp），上格「导航」呈 48×48 同心全圆高亮，下格「刷新」48dp，合计高 106dp。
private const val CollapsedNavButtonHeightDp = 48f
private const val CollapsedItemGapDp = 2f
private const val CollapsedRefreshButtonHeightDp = 48f
private const val CollapsedRefreshTopDp =
    PillInnerInsetDp + CollapsedNavButtonHeightDp + CollapsedItemGapDp

internal const val CollapsedPillWidthDp = HomeNavBarHeightDp
internal const val CollapsedPillHeightDp =
    CollapsedRefreshTopDp + CollapsedRefreshButtonHeightDp + PillInnerInsetDp

/** 竖屏 WebUI 侧边展开态页签槽位高度（38dp）：48×38dp 紧凑横向药丸高亮，压低整条展开高度（234dp）避免过大臃肿。 */
internal const val ExpandedTabSlotHeightDp = 38f

private val ExpandedActionsTopDp =
    PillInnerInsetDp + ExpandedTabSlotHeightDp * HomeTab.entries.size
private const val ExpandedDividerSlotHeightDp = 6f
private const val ExpandedRefreshButtonHeightDp = 32f
// 底部「终端」纯图标槽（36dp）：21dp 图标底边距外框底缘 11.5dp，自然落于 28dp 半圆弧内且不挤压裁切。
private const val ExpandedTerminalButtonHeightDp = 36f

internal const val ExpandedPillWidthDp = HomeNavBarHeightDp
internal val ExpandedPillHeightDp =
    ExpandedActionsTopDp + ExpandedDividerSlotHeightDp + ExpandedRefreshButtonHeightDp + ExpandedTerminalButtonHeightDp + PillInnerInsetDp

private val CollapsedPillWidth = CollapsedPillWidthDp.dp
private val CollapsedPillHeight = CollapsedPillHeightDp.dp
private val ExpandedPillWidth = ExpandedPillWidthDp.dp
private val ExpandedPillHeight = ExpandedPillHeightDp.dp

private val PillOuterPadding = PillInnerInsetDp.dp
private val CollapsedRefreshButtonHeight = CollapsedRefreshButtonHeightDp.dp
private val ExpandedDividerSlotHeight = ExpandedDividerSlotHeightDp.dp
private val ExpandedRefreshButtonHeight = ExpandedRefreshButtonHeightDp.dp
private val ExpandedTerminalButtonHeight = ExpandedTerminalButtonHeightDp.dp
private val PillEdgeMargin = 10.dp
private val PillBottomClearance = 84.dp

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

/**
 * 竖屏一体化液态玻璃导航（三层兄弟结构完整复刻 AndroidLiquidGlass LiquidBottomTabs 物理光学效果）：
 * - 在 WebUI 页签展现为可左右停靠 + 重力传感器阻尼换边 + 全向拖拽的灵动胶囊；
 * - 切离 WebUI 进入底栏形态时，底层胶囊、隐藏品牌橙放大层（tabsBackdrop）与独立浮起的水滴滑块同步联动：
 *   按住水滴可在四栏间跟手拖拽并鼓出底栏上下边界（78/56 ≈ 1.39x）、伴随速度挤压拉伸与色散折射，
 *   拖到底栏首格「WebUI」松手即可顺滑飞回侧边胶囊。
 */
@Composable
internal fun HomePortraitNavigation(
    selectedTab: HomeTab,
    backdrop: Backdrop,
    onRefreshWebUi: () -> Unit,
    onTabSelected: (HomeTab) -> Unit,
    onTerminalClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val settings = remember(context) { context.appDependencies.settings }
    val liquidHaptics = rememberLiquidHaptics()

    val isWebUi = selectedTab == HomeTab.WebUi
    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val latestOnTabSelected by rememberUpdatedState(onTabSelected)

    var dockSide by rememberSaveable {
        mutableStateOf(if (settings.webuiPillDockRight) PillDockSide.Right else DefaultPillDockSide)
    }
    var verticalBias by rememberSaveable {
        mutableFloatStateOf(clampPillVerticalBias(settings.webuiPillVerticalBias))
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var leavingFromExpanded by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val refreshSpin = remember { Animatable(0f) }
    val tiltDamper = remember { PillTiltDamper() }

    BackHandler(enabled = isWebUi && expanded) { expanded = false }

    val bottomProgress by animateFloatAsState(
        targetValue = if (isWebUi) 0f else 1f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "PortraitNavBottomProgress"
    )

    LaunchedEffect(isWebUi, expanded) {
        if (isWebUi) {
            leavingFromExpanded = false
            if (expanded) isDragging = false
        } else {
            expanded = false
            isDragging = false
        }
    }

    LaunchedEffect(isWebUi, bottomProgress) {
        if (!isWebUi && bottomProgress >= 0.997f) {
            leavingFromExpanded = false
        }
    }

    val effectiveSideExpanded = if (isWebUi) expanded else leavingFromExpanded
    val expandedSideProgress by animateFloatAsState(
        targetValue = if (effectiveSideExpanded) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "PortraitNavContentCrossfade"
    )

    if (isWebUi) {
        bindPillTiltSensor(
            currentSide = dockSide,
            interactionPaused = expanded || isDragging || bottomProgress > 0.05f,
            damper = tiltDamper,
            onDockSideChanged = { nextSide ->
                dockSide = nextSide
                interactionTick++
                settings.webuiPillDockRight = nextSide == PillDockSide.Right
            }
        )
    }

    var idleDimmed by remember { mutableStateOf(false) }
    LaunchedEffect(isWebUi, expanded, isDragging, interactionTick) {
        idleDimmed = false
        if (isWebUi && !expanded && !isDragging) {
            delay(PillIdleFadeDelayMs)
            idleDimmed = true
        }
    }
    val foregroundIdleAlpha by animateFloatAsState(
        targetValue = if (idleDimmed) 0.84f else 1f,
        animationSpec = tween(durationMillis = 360),
        label = "PortraitNavForegroundAlpha"
    )

    val triggerRefresh = {
        interactionTick++
        animationScope.launch {
            refreshSpin.snapTo(0f)
            refreshSpin.animateTo(360f, animationSpec = tween(durationMillis = 480))
        }
        onRefreshWebUi()
    }

    val statusBarTop = statusBarTopInset()
    @OptIn(ExperimentalLayoutApi::class)
    val imeBottomDp = with(density) { WindowInsets.imeAnimationTarget.getBottom(this).toDp().value }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidthDp = maxWidth.value
        val maxHeightDp = maxHeight.value
        val clampedBottomProgress = bottomProgress.coerceIn(0f, 1f)
        val clampedExpandedProgress = expandedSideProgress.coerceIn(0f, 1f)
        val dragAxis = resolvePortraitTabDragAxis(
            isWebUi = isWebUi,
            expanded = expanded,
            expandedProgress = clampedExpandedProgress,
            bottomProgress = clampedBottomProgress
        )
        val latestDragAxis by rememberUpdatedState(dragAxis)
        val latestClampedBottomProgress by rememberUpdatedState(clampedBottomProgress)

        val touchSlopPx = LocalViewConfiguration.current.touchSlop
        val pillInnerInsetPx = with(density) { PillInnerInsetDp.dp.toPx() }
        val expandedTabSlotHeightPx by rememberUpdatedState(with(density) { ExpandedTabSlotHeightDp.dp.toPx() })
        val expandedPillHeightPx = with(density) { ExpandedPillHeightDp.dp.toPx() }
        val bottomTabBarWidth = computeBottomTabBarWidthDp(maxWidthDp)
        val bottomSingleTabWidth = (bottomTabBarWidth - PillInnerInsetDp * 2f) / HomeTab.entries.size
        val bottomSingleTabWidthPx by rememberUpdatedState(with(density) { bottomSingleTabWidth.dp.toPx() })
        val bottomTabBarWidthPx = with(density) { bottomTabBarWidth.dp.toPx() }
        val maxPanelOffsetPx = with(density) { 4f.dp.toPx() }

        val tabsCount = HomeTab.entries.size
        val panelOffsetAnim = remember { Animatable(0f) }
        val activeSpanPx = if (clampedBottomProgress < 0.5f) expandedPillHeightPx else bottomTabBarWidthPx
        val panelOffsetPx by remember(activeSpanPx, maxPanelOffsetPx) {
            derivedStateOf {
                computeLiquidPanelOffsetPx(
                    accumulatedOffsetPx = panelOffsetAnim.value,
                    containerSpanPx = activeSpanPx,
                    maxPanelOffsetPx = maxPanelOffsetPx
                )
            }
        }
        val verticalSideFactor = clampedExpandedProgress * (1f - clampedBottomProgress)
        val barPanelShiftXPx = panelOffsetPx * clampedBottomProgress
        val barPanelShiftYPx = panelOffsetPx * verticalSideFactor
        val latestBarPanelShiftXPx by rememberUpdatedState(barPanelShiftXPx)
        val latestBarPanelShiftYPx by rememberUpdatedState(barPanelShiftYPx)

        var currentTabIndex by remember { mutableIntStateOf(selectedTab.ordinal) }
        var isTabDragging by remember { mutableStateOf(false) }
        var dragStartAxis by remember { mutableStateOf(PortraitTabDragAxis.None) }
        var dragMovedDistancePx by remember { mutableFloatStateOf(0f) }
        var lastHapticTabIndex by remember { mutableIntStateOf(selectedTab.ordinal) }
        val tabDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTab.ordinal.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                pressedScaleProvider = {
                    if (latestDragAxis == PortraitTabDragAxis.VerticalExpandedSide) 70f / 56f else 78f / 56f
                },
                onDragStarted = {
                    dragStartAxis = latestDragAxis
                    dragMovedDistancePx = 0f
                    isTabDragging = true
                    interactionTick++
                },
                onDragStopped = {
                    isTabDragging = false
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    if (dragStartAxis == PortraitTabDragAxis.VerticalExpandedSide) {
                        when (
                            resolveExpandedSideDragOutcome(
                                targetIndex = targetIndex,
                                movedDistancePx = dragMovedDistancePx,
                                touchSlopPx = touchSlopPx
                            )
                        ) {
                            ExpandedSideDragOutcome.CollapseMenu -> {
                                // 首格轻点不带位移松手 -> 直接由 release() 收拢水滴并同步收起侧边菜单
                                interactionTick++
                                expanded = false
                                if (abs(value) > 0.001f) {
                                    updateValue(0f)
                                }
                            }
                            ExpandedSideDragOutcome.SwitchTab -> {
                                leavingFromExpanded = true
                                expanded = false
                                currentTabIndex = targetIndex
                                animateToValue(targetIndex.toFloat())
                            }
                            ExpandedSideDragOutcome.StayExpanded -> {
                                currentTabIndex = HomeTab.WebUi.ordinal
                                animateToValue(0f)
                            }
                        }
                    } else {
                        currentTabIndex = targetIndex
                        animateToValue(targetIndex.toFloat())
                    }
                    dragStartAxis = PortraitTabDragAxis.None
                    animationScope.launch {
                        panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDragCancelled = {
                    isTabDragging = false
                    if (dragStartAxis == PortraitTabDragAxis.VerticalExpandedSide) {
                        currentTabIndex = HomeTab.WebUi.ordinal
                        animateToValue(0f)
                    } else {
                        val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                        currentTabIndex = targetIndex
                        animateToValue(targetIndex.toFloat())
                    }
                    dragStartAxis = PortraitTabDragAxis.None
                    animationScope.launch {
                        panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    dragMovedDistancePx += dragAmount.getDistance()
                    when (dragStartAxis) {
                        PortraitTabDragAxis.VerticalExpandedSide -> {
                            val slotHeight = expandedTabSlotHeightPx.coerceAtLeast(1f)
                            val step = dragAmount.y / slotHeight
                            updateValue((targetValue + step).fastCoerceIn(0f, (tabsCount - 1).toFloat()))
                            animationScope.launch {
                                panelOffsetAnim.snapTo(panelOffsetAnim.value + dragAmount.y)
                            }
                        }
                        PortraitTabDragAxis.HorizontalBottom -> {
                            val singleWidth = bottomSingleTabWidthPx.coerceAtLeast(1f)
                            val step = dragAmount.x / singleWidth * if (isLtr) 1f else -1f
                            updateValue((targetValue + step).fastCoerceIn(0f, (tabsCount - 1).toFloat()))
                            animationScope.launch {
                                panelOffsetAnim.snapTo(panelOffsetAnim.value + dragAmount.x)
                            }
                        }
                        PortraitTabDragAxis.None -> Unit
                    }
                }
            )
        }

        LaunchedEffect(dragAxis) {
            if (dragAxis == PortraitTabDragAxis.None && isTabDragging) {
                isTabDragging = false
                dragStartAxis = PortraitTabDragAxis.None
                panelOffsetAnim.snapTo(0f)
            }
        }
        LaunchedEffect(selectedTab) {
            // 点按标签或拖拽松手吸附到新页签时若跨格尚未震动，则补充一次落格反馈
            if (lastHapticTabIndex != selectedTab.ordinal) {
                lastHapticTabIndex = selectedTab.ordinal
                liquidHaptics.performTabStep()
            }
            if (currentTabIndex != selectedTab.ordinal) {
                currentTabIndex = selectedTab.ordinal
            }
            if (abs(tabDragAnimation.targetValue - selectedTab.ordinal.toFloat()) > 0.01f) {
                tabDragAnimation.animateToValue(selectedTab.ordinal.toFloat())
            }
        }
        LaunchedEffect(tabDragAnimation, liquidHaptics) {
            // 拖拽过程中滑过选项中线死区瞬间触发一次“哒”，避免停在边界时抖动连震
            snapshotFlow { isTabDragging to tabDragAnimation.value }
                .collect { (dragging, continuousValue) ->
                    if (!dragging) return@collect
                    val nextSlot = resolveLiquidHapticSlot(
                        currentSlot = lastHapticTabIndex,
                        continuousValue = continuousValue,
                        itemCount = tabsCount
                    )
                    if (nextSlot != lastHapticTabIndex) {
                        lastHapticTabIndex = nextSlot
                        liquidHaptics.performTabStep()
                    }
                }
        }
        LaunchedEffect(tabDragAnimation) {
            snapshotFlow { currentTabIndex }
                .drop(1)
                .collectLatest { index ->
                    val targetTab = HomeTab.entries.getOrNull(index) ?: return@collectLatest
                    if (targetTab != latestSelectedTab) {
                        latestOnTabSelected(targetTab)
                    }
                }
        }

        val barHighlight = remember(animationScope, isLtr) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    if (latestClampedBottomProgress < 0.5f) {
                        val slotH = expandedTabSlotHeightPx.coerceAtLeast(1f)
                        Offset(
                            size.width * 0.5f,
                            pillInnerInsetPx + (tabDragAnimation.value + 0.5f) * slotH + latestBarPanelShiftYPx
                        )
                    } else {
                        val slotW = (size.width / tabsCount).coerceAtLeast(1f)
                        val centerX = if (isLtr) {
                            (tabDragAnimation.value + 0.5f) * slotW + latestBarPanelShiftXPx
                        } else {
                            size.width - (tabDragAnimation.value + 0.5f) * slotW + latestBarPanelShiftXPx
                        }
                        Offset(centerX, size.height * 0.5f)
                    }
                }
            )
        }

        if (isWebUi && expanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            interactionTick++
                            expanded = false
                        }
                    )
            )
        }

        val sideTargetWidth = if (effectiveSideExpanded) ExpandedPillWidth else CollapsedPillWidth
        val sideTargetHeight = if (effectiveSideExpanded) ExpandedPillHeight else CollapsedPillHeight

        val sideAnimatedWidth by animateDpAsState(
            targetValue = sideTargetWidth,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "PortraitNavSideWidth"
        )
        val sideAnimatedHeight by animateDpAsState(
            targetValue = sideTargetHeight,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "PortraitNavSideHeight"
        )

        val minTopDp = statusBarTop.value + 16f
        val sideMaxHeightDp = (maxHeightDp - imeBottomDp).coerceAtLeast(minTopDp + CollapsedPillHeightDp)
        val sideDockedTop = computeDockedPillTopDp(
            minTopDp = minTopDp,
            maxHeightDp = sideMaxHeightDp,
            bottomClearanceDp = PillBottomClearance.value,
            collapsedHeightDp = CollapsedPillHeight.value,
            // 停靠底部附近展开时按展开后单一胶囊总高（234dp）避让，防止底层刷新/终端区超出安全范围。
            currentHeightDp = if (effectiveSideExpanded) ExpandedPillHeightDp else CollapsedPillHeightDp,
            verticalBias = verticalBias
        ).dp
        val sideDockedLeft =
            if (dockSide == PillDockSide.Right) maxWidth - sideTargetWidth - PillEdgeMargin else PillEdgeMargin

        val maxWidthPx = with(density) { maxWidth.toPx() }
        val minTopPx = with(density) { minTopDp.dp.toPx() }
        val maxTopCollapsedPx = with(density) {
            (sideMaxHeightDp - CollapsedPillHeight.value - PillBottomClearance.value)
                .coerceAtLeast(minTopDp).dp.toPx()
        }
        val sideWidthPx = with(density) { sideTargetWidth.toPx() }

        var dragLeftPx by remember { mutableFloatStateOf(0f) }
        var dragTopPx by remember { mutableFloatStateOf(0f) }
        val latestSideDockedLeftPx by rememberUpdatedState(with(density) { sideDockedLeft.toPx() })
        val latestSideDockedTopPx by rememberUpdatedState(with(density) { sideDockedTop.toPx() })

        val targetSideLeftDp = if (isDragging) with(density) { dragLeftPx.toDp() } else sideDockedLeft
        val targetSideTopDp = if (isDragging) with(density) { dragTopPx.toDp() } else sideDockedTop

        val sideAnimatedLeft by animateDpAsState(
            targetValue = targetSideLeftDp,
            animationSpec = if (isDragging) snap() else spring(
                dampingRatio = 0.86f,
                stiffness = 260f
            ),
            label = "PortraitNavSideX"
        )
        val sideAnimatedTop by animateDpAsState(
            targetValue = targetSideTopDp,
            animationSpec = if (isDragging) snap() else spring(
                dampingRatio = 0.86f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "PortraitNavSideY"
        )

        val frame = computePortraitNavFrame(
            maxWidthDp = maxWidthDp,
            maxHeightDp = maxHeightDp,
            sideLeftDp = sideAnimatedLeft.value,
            sideTopDp = sideAnimatedTop.value,
            sideWidthDp = sideAnimatedWidth.value,
            sideHeightDp = sideAnimatedHeight.value,
            expandedProgress = expandedSideProgress,
            bottomProgress = bottomProgress
        )

        val continuousTabValue = tabDragAnimation.value.coerceIn(0f, (tabsCount - 1).toFloat())

        val indicatorX = lerpFloat(
            PillInnerInsetDp,
            PillInnerInsetDp + bottomSingleTabWidth * continuousTabValue,
            clampedBottomProgress
        )
        val indicatorY = lerpFloat(
            PillInnerInsetDp + ExpandedTabSlotHeightDp * continuousTabValue * clampedExpandedProgress,
            PillInnerInsetDp,
            clampedBottomProgress
        )
        val indicatorHeight = lerpFloat(
            lerpFloat(CollapsedNavButtonHeightDp, ExpandedTabSlotHeightDp, clampedExpandedProgress),
            HomeTabButtonHeightDp,
            clampedBottomProgress
        )

        val navLabelAlpha = ((1f - clampedExpandedProgress) * (1f - clampedBottomProgress * 2f)).coerceIn(0f, 1f)
        val collapsedRefreshAlpha = ((1f - clampedExpandedProgress) * (1f - clampedBottomProgress)).coerceIn(0f, 1f)
        val expandedExtraAlpha = (clampedExpandedProgress * (1f - clampedBottomProgress * 2f)).coerceIn(0f, 1f)
        val secondaryTabsAlpha = maxOf(clampedExpandedProgress, clampedBottomProgress)
        val compactExpandedFraction = clampedExpandedProgress * (1f - clampedBottomProgress)
        val primaryContentAlpha = lerpFloat(
            1f,
            if (selectedTab == HomeTab.WebUi) 1f else HomeNavInactiveContentAlpha,
            clampedBottomProgress
        ) * foregroundIdleAlpha
        val primaryIconGap = lerpFloat(2f, 1.5f, compactExpandedFraction).dp

        // 竖屏 WebUI 侧边胶囊（56dp 宽）与底栏（56dp 高）统一采用全圆 Capsule()，4dp 内边距后内滑块 48dp 严格同心。
        val barShape = { HomeNavBarShape }
        val indicatorShape = { HomeNavIndicatorShape }
        val tabsBackdrop = rememberLayerBackdrop()
        val combinedIndicatorBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
        val refractionActive by remember(tabDragAnimation) {
            derivedStateOf {
                shouldRenderLiquidTabRefraction(tabDragAnimation.pressProgress, isTabDragging)
            }
        }
        val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val activeTabGestures = dragAxis != PortraitTabDragAxis.None
        val activeScaleFactor = maxOf(clampedBottomProgress, verticalSideFactor)
        val capsuleWidthPx = with(density) { frame.capsuleWidthDp.dp.toPx() }.coerceAtLeast(1f)
        val capsuleSpanPx = lerpFloat(expandedPillHeightPx, capsuleWidthPx, clampedBottomProgress).coerceAtLeast(1f)
        val pressExpansionPx = with(density) { lerpFloat(12f, 16f, clampedBottomProgress).dp.toPx() }
        val barPressScale = lerp(
            1f,
            1f + pressExpansionPx / capsuleSpanPx,
            tabDragAnimation.pressProgress * activeScaleFactor
        )

        // ── 兄弟层 1：基础液态玻璃胶囊 + 全部交互按钮 ───────────────────────────────────
        Box(
            modifier = Modifier
                .offset { IntOffset(frame.capsuleLeftDp.dp.roundToPx(), frame.capsuleTopDp.dp.roundToPx()) }
                .size(width = frame.capsuleWidthDp.dp, height = frame.capsuleHeightDp.dp)
                .graphicsLayer {
                    translationX = barPanelShiftXPx
                    translationY = barPanelShiftYPx
                }
                .liquidGlassSurface(
                    backdrop = backdrop,
                    shape = barShape,
                    blurRadius = 10.dp,
                    refractionHeight = 20.dp,
                    refractionAmount = 24.dp,
                    surfaceColor = LiquidBarSurface,
                    layerBlock = {
                        scaleX = barPressScale
                        scaleY = barPressScale
                    }
                )
                .then(barHighlight.modifier)
                .pointerInput(isWebUi, expanded, maxWidthPx, minTopPx, maxTopCollapsedPx, sideWidthPx) {
                    // 仅 WebUI 收起态侧边胶囊开放整条全向拖移；底栏态锁定外框以让水滴自由左右滑动
                    if (!isWebUi || expanded) return@pointerInput
                    detectDragGestures(
                        onDragStart = {
                            dragLeftPx = latestSideDockedLeftPx
                            dragTopPx = latestSideDockedTopPx
                            isDragging = true
                            interactionTick++
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val maxLeft = (maxWidthPx - sideWidthPx - 4f).coerceAtLeast(4f)
                            dragLeftPx = (dragLeftPx + dragAmount.x).coerceIn(4f, maxLeft)
                            dragTopPx = (dragTopPx + dragAmount.y).coerceIn(minTopPx, maxTopCollapsedPx)
                        },
                        onDragEnd = {
                            val centerX = dragLeftPx + sideWidthPx * 0.5f
                            dockSide = resolvePillDockSide(centerX, maxWidthPx)
                            val span = (maxTopCollapsedPx - minTopPx).coerceAtLeast(1f)
                            verticalBias = clampPillVerticalBias((dragTopPx - minTopPx) / span)
                            settings.webuiPillDockRight = dockSide == PillDockSide.Right
                            settings.webuiPillVerticalBias = verticalBias
                            tiltDamper.onManualDock()
                            isDragging = false
                            interactionTick++
                        },
                        onDragCancel = {
                            isDragging = false
                        }
                    )
                }
        ) {
            // 侧边展开态下方附加工具区留在单一胶囊内（分隔线 + 紧凑刷新与终端图标钮），不拆分到外侧悬浮圆。
            if (expandedExtraAlpha > 0.01f) {
                ExpandedExtraActions(
                    spinDegrees = refreshSpin.value,
                    enabled = isWebUi && expanded && clampedBottomProgress < 0.15f,
                    onRefresh = {
                        triggerRefresh()
                        expanded = false
                    },
                    onOpenTerminal = {
                        expanded = false
                        onTerminalClick()
                    },
                    modifier = Modifier
                        .zIndex(if (isWebUi && expanded) 1f else 0f)
                        .offset { IntOffset(PillOuterPadding.roundToPx(), ExpandedActionsTopDp.dp.roundToPx()) }
                        .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                        .size(
                            width = ExpandedPillWidth - PillOuterPadding * 2,
                            height = ExpandedDividerSlotHeight + ExpandedRefreshButtonHeight + ExpandedTerminalButtonHeight
                        )
                        .graphicsLayer { alpha = expandedExtraAlpha * foregroundIdleAlpha }
                )
            }

            if (collapsedRefreshAlpha > 0.01f) {
                PillLabeledButton(
                    label = "刷新",
                    contentAlpha = HomeNavInactiveContentAlpha,
                    enabled = isWebUi && !expanded && clampedBottomProgress < 0.15f,
                    onClick = { triggerRefresh() },
                    modifier = Modifier
                        .zIndex(if (isWebUi && !expanded) 1f else 0f)
                        .offset { IntOffset(PillOuterPadding.roundToPx(), CollapsedRefreshTopDp.dp.roundToPx()) }
                        .size(width = CollapsedPillWidth - PillOuterPadding * 2, height = CollapsedRefreshButtonHeight)
                        .graphicsLayer { alpha = collapsedRefreshAlpha * foregroundIdleAlpha }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "刷新 WebUI",
                        tint = TextPrimary,
                        modifier = Modifier
                            .size(21.dp)
                            .graphicsLayer { rotationZ = refreshSpin.value }
                    )
                }
            }

            if (secondaryTabsAlpha > 0.01f) {
                HomeTab.entries
                    .filter { it != HomeTab.WebUi }
                    .forEach { tab ->
                        val pos = frame.tabPositionsDp[tab.ordinal]
                        Box(
                            modifier = Modifier
                                .zIndex(if (!isWebUi || expanded) 2f else 0f)
                                .offset { IntOffset(pos.first.dp.roundToPx(), pos.second.dp.roundToPx()) }
                                .size(width = frame.tabWidthDp.dp, height = frame.tabHeightDp.dp)
                                .graphicsLayer { alpha = secondaryTabsAlpha * foregroundIdleAlpha },
                            contentAlignment = Alignment.Center
                        ) {
                            HomeTabButton(
                                tab = tab,
                                selected = selectedTab == tab,
                                compact = clampedBottomProgress < 0.5f,
                                narrowVertical = clampedBottomProgress < 0.5f,
                                enabled = !isWebUi || expanded,
                                onClick = {
                                    if (isWebUi) {
                                        leavingFromExpanded = true
                                        expanded = false
                                    }
                                    onTabSelected(tab)
                                },
                                modifier = Modifier.size(width = frame.tabWidthDp.dp, height = frame.tabHeightDp.dp)
                            )
                        }
                    }
            }

            val primaryPos = frame.tabPositionsDp[HomeTab.WebUi.ordinal]
            WebUiPrimaryTabSlot(
                isWebUi = isWebUi,
                expanded = expanded,
                navLabelAlpha = navLabelAlpha,
                primaryContentAlpha = primaryContentAlpha,
                primaryIconGap = primaryIconGap,
                compactExpandedFraction = compactExpandedFraction,
                accentHighlighted = false,
                clickableEnabled = true,
                onClick = {
                    if (isWebUi) {
                        interactionTick++
                        expanded = !expanded
                    } else {
                        onTabSelected(HomeTab.WebUi)
                    }
                },
                modifier = Modifier
                    .zIndex(3f)
                    .offset { IntOffset(primaryPos.first.dp.roundToPx(), primaryPos.second.dp.roundToPx()) }
                    .size(width = frame.tabWidthDp.dp, height = indicatorHeight.dp)
            )
        }

        // ── 兄弟层 2：隐身品牌橙放大高亮层（仅按压或拖拽滑块时按需挂载，静止态零离屏着色器开销）───────
        if (refractionActive) {
            val targetAccentScale = lerpFloat(HomeNavVerticalAccentScale, HomeNavHorizontalAccentScale, clampedBottomProgress)
            CompositionLocalProvider(
                LocalLiquidBottomTabScale provides {
                    lerp(1f, targetAccentScale, tabDragAnimation.pressProgress)
                }
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(frame.capsuleLeftDp.dp.roundToPx(), frame.capsuleTopDp.dp.roundToPx()) }
                        .size(width = frame.capsuleWidthDp.dp, height = frame.capsuleHeightDp.dp)
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer {
                            translationX = barPanelShiftXPx
                            translationY = barPanelShiftYPx
                        }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = barShape,
                            effects = {
                                val progress = tabDragAnimation.pressProgress
                                vibrancy()
                                blur(10f.dp.toPx())
                                lens(20f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = tabDragAnimation.pressProgress)
                            },
                            shadow = null,
                            layerBlock = {
                                scaleX = barPressScale
                                scaleY = barPressScale
                            },
                            onDrawSurface = {
                                drawRect(if (hasRenderEffect) LiquidBarSurface else GlassFallbackSurface)
                            }
                        )
                        .then(barHighlight.modifier)
                ) {
                    if (expandedExtraAlpha > 0.01f) {
                        ExpandedExtraActions(
                            spinDegrees = refreshSpin.value,
                            enabled = false,
                            clickableEnabled = false,
                            onRefresh = {},
                            onOpenTerminal = {},
                            modifier = Modifier
                                .offset { IntOffset(PillOuterPadding.roundToPx(), ExpandedActionsTopDp.dp.roundToPx()) }
                                .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                                .size(
                                    width = ExpandedPillWidth - PillOuterPadding * 2,
                                    height = ExpandedDividerSlotHeight + ExpandedRefreshButtonHeight + ExpandedTerminalButtonHeight
                                )
                                .graphicsLayer { alpha = expandedExtraAlpha * foregroundIdleAlpha }
                        )
                    }

                    if (secondaryTabsAlpha > 0.01f) {
                        HomeTab.entries
                            .filter { it != HomeTab.WebUi }
                            .forEach { tab ->
                                val pos = frame.tabPositionsDp[tab.ordinal]
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(pos.first.dp.roundToPx(), pos.second.dp.roundToPx()) }
                                        .size(width = frame.tabWidthDp.dp, height = frame.tabHeightDp.dp)
                                        .graphicsLayer { alpha = secondaryTabsAlpha * foregroundIdleAlpha },
                                    contentAlignment = Alignment.Center
                                ) {
                                    HomeTabButton(
                                        tab = tab,
                                        selected = true,
                                        compact = clampedBottomProgress < 0.5f,
                                        narrowVertical = clampedBottomProgress < 0.5f,
                                        enabled = false,
                                        accentHighlighted = true,
                                        clickableEnabled = false,
                                        onClick = {},
                                        modifier = Modifier.size(width = frame.tabWidthDp.dp, height = frame.tabHeightDp.dp)
                                    )
                                }
                            }
                    }

                    val primaryPos = frame.tabPositionsDp[HomeTab.WebUi.ordinal]
                    WebUiPrimaryTabSlot(
                        isWebUi = isWebUi,
                        expanded = expanded,
                        navLabelAlpha = navLabelAlpha,
                        primaryContentAlpha = foregroundIdleAlpha,
                        primaryIconGap = primaryIconGap,
                        compactExpandedFraction = compactExpandedFraction,
                        accentHighlighted = true,
                        clickableEnabled = false,
                        onClick = {},
                        modifier = Modifier
                            .offset { IntOffset(primaryPos.first.dp.roundToPx(), primaryPos.second.dp.roundToPx()) }
                            .size(width = frame.tabWidthDp.dp, height = indicatorHeight.dp)
                    )
                }
            }
        }

        // ── 兄弟层 3：顶层游走液态水滴滑块（静止态仅绘轻量底色圆角，按压拖拽期间切换实时双源透镜折射）──
        val fallbackBorder = if (hasRenderEffect) Modifier else Modifier.border(0.6.dp, GlassStroke, HomeNavIndicatorShape)
        val indicatorSurfaceModifier = if (refractionActive) {
            Modifier.drawBackdrop(
                backdrop = combinedIndicatorBackdrop,
                shape = indicatorShape,
                effects = {
                    val progress = tabDragAnimation.pressProgress
                    lens(
                        refractionHeight = 10f.dp.toPx() * progress,
                        refractionAmount = 14f.dp.toPx() * progress,
                        chromaticAberration = true
                    )
                },
                highlight = {
                    Highlight.Default.copy(alpha = tabDragAnimation.pressProgress)
                },
                shadow = {
                    Shadow(alpha = tabDragAnimation.pressProgress)
                },
                innerShadow = {
                    val progress = tabDragAnimation.pressProgress
                    InnerShadow(radius = 8f.dp * progress, alpha = progress)
                },
                layerBlock = {
                    scaleX = tabDragAnimation.scaleX
                    scaleY = tabDragAnimation.scaleY
                    val velocity = tabDragAnimation.velocity / 10f
                    if (latestClampedBottomProgress < 0.5f) {
                        // 竖屏 WebUI 展开胶囊内纵向拖拽水滴时，沿移动轴（Y 轴）拉伸并在 X 轴微收腰。
                        scaleY /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleX *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    } else {
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    }
                },
                onDrawSurface = {
                    val progress = tabDragAnimation.pressProgress
                    drawRect(GlassSelectedIndicator, alpha = 1f - progress)
                    drawRect(Color.Black.copy(alpha = 0.03f * progress))
                }
            )
        } else {
            Modifier.background(GlassSelectedIndicator, HomeNavIndicatorShape)
        }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (frame.capsuleLeftDp + indicatorX).dp.roundToPx(),
                        (frame.capsuleTopDp + indicatorY).dp.roundToPx()
                    )
                }
                .size(width = frame.tabWidthDp.dp, height = indicatorHeight.dp)
                .graphicsLayer {
                    translationX = barPanelShiftXPx
                    translationY = barPanelShiftYPx
                    alpha = foregroundIdleAlpha
                }
                .then(if (activeTabGestures) barHighlight.gestureModifier else Modifier)
                .then(if (activeTabGestures) tabDragAnimation.modifier else Modifier)
                .then(indicatorSurfaceModifier)
                .then(fallbackBorder)
        )

        // 切离 WebUI 进入底栏时分离出右下角 56dp 正圆终端按钮，切回 WebUI 时收拢消融回单一侧边胶囊。
        if (frame.terminalAlpha > 0.02f) {
            HomeTerminalButton(
                backdrop = backdrop,
                onClick = onTerminalClick,
                enabled = !isWebUi && clampedBottomProgress > 0.5f,
                size = PortraitBottomBarHeight,
                shape = { HomeNavBarShape },
                modifier = Modifier
                    .offset { IntOffset(frame.terminalLeftDp.dp.roundToPx(), frame.terminalTopDp.dp.roundToPx()) }
                    .graphicsLayer {
                        alpha = frame.terminalAlpha * foregroundIdleAlpha
                        scaleX = frame.terminalScale
                        scaleY = frame.terminalScale
                    }
            )
        }
    }
}

/** 首格「导航 / WebUI」共享槽位：底层与隐藏折射层复用，并在侧边展开态同步缩小到紧凑尺寸对齐另外三格页签。 */
@Composable
private fun WebUiPrimaryTabSlot(
    isWebUi: Boolean,
    expanded: Boolean,
    navLabelAlpha: Float,
    primaryContentAlpha: Float,
    primaryIconGap: Dp,
    compactExpandedFraction: Float,
    accentHighlighted: Boolean,
    clickableEnabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accentScale = LocalLiquidBottomTabScale.current
    val textColor = if (accentHighlighted) BrandOrange else TextPrimary
    val bottomTypography = resolveHomeNavTabTypography(narrowVertical = false, compact = false)
    val expandedTypography = resolveHomeNavTabTypography(narrowVertical = true, compact = true)
    val fontScale = LocalDensity.current.fontScale
    val iconSize = lerpFloat(bottomTypography.iconSizeDp, expandedTypography.iconSizeDp, compactExpandedFraction).dp
    val labelSize = clampHomeNavLabelSp(
        lerpFloat(bottomTypography.labelSizeSp, expandedTypography.labelSizeSp, compactExpandedFraction),
        fontScale
    ).sp
    val labelLineHeight = clampHomeNavLabelSp(
        lerpFloat(bottomTypography.labelLineHeightSp, expandedTypography.labelLineHeightSp, compactExpandedFraction),
        fontScale
    ).sp
    val clickMod = if (clickableEnabled) {
        Modifier.clickable(interactionSource = null, indication = null, onClick = onClick)
    } else {
        Modifier
    }

    // 槽位不做 Capsule 裁剪，避免隐身高亮层拖拽放大时外缘字角或下行笔画被圆角裁切。
    Box(
        modifier = modifier.then(clickMod),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                alpha = primaryContentAlpha
                if (accentHighlighted) {
                    val s = accentScale()
                    scaleX = s
                    scaleY = s
                }
            }
        ) {
            Image(
                painter = painterResource(R.drawable.ic_maibot),
                contentDescription = if (isWebUi && !expanded) "展开导航菜单" else HomeTab.WebUi.label,
                modifier = Modifier.size(iconSize)
            )
            Spacer(modifier = Modifier.height(primaryIconGap))
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "导航",
                    color = textColor,
                    fontSize = labelSize,
                    fontWeight = FontWeight.Bold,
                    lineHeight = labelLineHeight,
                    letterSpacing = 0.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.graphicsLayer { alpha = navLabelAlpha }
                )
                Text(
                    text = HomeTab.WebUi.label,
                    color = textColor,
                    fontSize = labelSize,
                    fontWeight = FontWeight.Bold,
                    lineHeight = labelLineHeight,
                    letterSpacing = 0.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.graphicsLayer { alpha = 1f - navLabelAlpha }
                )
            }
        }
    }
}

/** 竖屏 WebUI 展开胶囊内下半区操作：细分隔线 + 紧凑纯图标「刷新」与「终端」槽（共用 78% 灰白前景）。 */
@Composable
private fun ExpandedExtraActions(
    spinDegrees: Float,
    enabled: Boolean,
    clickableEnabled: Boolean = true,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 隐身折射克隆层严禁挂 clickable(enabled = false) 拦截底层点击：仅交互层挂入点击语义。
    val refreshClickMod = if (clickableEnabled) {
        Modifier.clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onRefresh)
    } else {
        Modifier
    }
    val terminalClickMod = if (clickableEnabled) {
        Modifier.clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onOpenTerminal)
    } else {
        Modifier
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ExpandedDividerSlotHeight),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
                    .height(0.8.dp)
                    .background(CardDivider)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ExpandedRefreshButtonHeight)
                .clip(HomeNavIndicatorShape)
                .then(refreshClickMod),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "刷新 WebUI",
                tint = TextPrimary.copy(alpha = HomeNavInactiveContentAlpha),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = spinDegrees }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ExpandedTerminalButtonHeight)
                .clip(HomeNavIndicatorShape)
                .then(terminalClickMod),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_terminal_code),
                contentDescription = "查看终端输出",
                tint = TextPrimary.copy(alpha = HomeNavInactiveContentAlpha),
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

/** 胶囊内部通用“上图下文”动作按钮：取消默认灰水波纹并统一采用全圆 Capsule() 裁剪。 */
@Composable
private fun PillLabeledButton(
    label: String,
    contentAlpha: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    // 收起态「刷新」为两字标签，沿用与上方「导航」同档的 11sp 清晰排版，落入 48×48 全圆仍有充足弦宽。
    val typography = resolveHomeNavTabTypography(narrowVertical = false, compact = false)
    val fontScale = LocalDensity.current.fontScale
    val labelSize = clampHomeNavLabelSp(typography.labelSizeSp, fontScale).sp
    val labelLineHeight = clampHomeNavLabelSp(typography.labelLineHeightSp, fontScale).sp
    Box(
        modifier = modifier
            .clip(HomeNavIndicatorShape)
            .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            icon()
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = labelSize,
                fontWeight = FontWeight.Bold,
                lineHeight = labelLineHeight,
                letterSpacing = 0.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible
            )
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction
