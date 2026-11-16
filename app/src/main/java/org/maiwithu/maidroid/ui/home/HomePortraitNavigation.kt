package org.maiwithu.maidroid.ui.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.ui.component.BackdropBlurView
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassShadow
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

private const val PillIdleFadeDelayMs = 3_000L

private const val PillInnerInsetDp = 4f
private const val CollapsedNavButtonHeightDp = 54f
private const val CollapsedItemGapDp = 4f
private const val CollapsedRefreshButtonHeightDp = 52f
private const val CollapsedRefreshTopDp =
    PillInnerInsetDp + CollapsedNavButtonHeightDp + CollapsedItemGapDp

internal const val CollapsedPillWidthDp = 64f
internal const val CollapsedPillHeightDp =
    CollapsedRefreshTopDp + CollapsedRefreshButtonHeightDp + PillInnerInsetDp

private const val ExpandedActionsTopDp = PillInnerInsetDp + HomeTabButtonHeightDp * 3f
private const val ExpandedDividerSlotHeightDp = 9f
private const val ExpandedActionButtonHeightDp = 54f

internal const val ExpandedPillWidthDp = 74f
internal const val ExpandedPillHeightDp =
    ExpandedActionsTopDp + ExpandedDividerSlotHeightDp + ExpandedActionButtonHeightDp * 2f + PillInnerInsetDp

private val CollapsedPillWidth = CollapsedPillWidthDp.dp
private val CollapsedPillHeight = CollapsedPillHeightDp.dp
private val ExpandedPillWidth = ExpandedPillWidthDp.dp
private val ExpandedPillHeight = ExpandedPillHeightDp.dp

private val PillOuterPadding = PillInnerInsetDp.dp
private val CollapsedRefreshButtonHeight = CollapsedRefreshButtonHeightDp.dp
private val ExpandedDividerSlotHeight = ExpandedDividerSlotHeightDp.dp
private val ExpandedActionButtonHeight = ExpandedActionButtonHeightDp.dp
private val PillEdgeMargin = 10.dp
private val PillBottomClearance = 84.dp

/**
 * 竖屏统一导航在任意过渡帧的二维几何描述（单位 dp）：
 * 既描述侧边/底部共用的主毛玻璃胶囊外框及内部 3 颗标签位置，也描述独立圆形终端按钮的分离/融入轨迹。
 */
internal data class PortraitNavFrame(
    val capsuleLeftDp: Float,
    val capsuleTopDp: Float,
    val capsuleWidthDp: Float,
    val capsuleHeightDp: Float,
    val tabWidthDp: Float,
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
 * - [bottomProgress] 为 0 时处于侧边胶囊态，为 1 时精确对齐底部三标签 + 右下终端按钮布局；
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
    val tabPositions = HomeTab.entries.indices.map { index ->
        val sideSlotY = PillInnerInsetDp + (HomeTabButtonHeight.value * index) * clampedExpanded
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
        tabPositionsDp = tabPositions,
        terminalLeftDp = terminalLeft,
        terminalTopDp = terminalTop,
        terminalAlpha = terminalAlpha,
        terminalScale = terminalScale
    )
}

/**
 * 竖屏一体化毛玻璃导航：
 * - 在 WebUI 页签展现为可左右停靠（默认左缘）+ 重力传感器三级阻尼换边 + 收起态全向拖拽的灵动胶囊；
 * - 切往消息平台/设置时，同一块毛玻璃胶囊从所在左/右侧边缘平滑滑移并扇形展开成底部三栏导航，右下圆形终端钮同步分离就位；
 * - 切回 WebUI 时沿对称轨迹从底栏收拢飞回离开前的同一侧边缘，实现无缝空间连续过渡。
 */
@Composable
internal fun HomePortraitNavigation(
    selectedTab: HomeTab,
    blurTarget: BlurTarget?,
    onRefreshWebUi: () -> Unit,
    onTabSelected: (HomeTab) -> Unit,
    onTerminalClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val settings = remember(context) { context.appDependencies.settings }

    val isWebUi = selectedTab == HomeTab.WebUi

    var dockSide by rememberSaveable {
        mutableStateOf(if (settings.webuiPillDockRight) PillDockSide.Right else DefaultPillDockSide)
    }
    var verticalBias by rememberSaveable {
        mutableFloatStateOf(clampPillVerticalBias(settings.webuiPillVerticalBias))
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    // 从展开态切离 WebUI 时保留侧边展开锚点直至动画抵达底栏，确保扇形展开与滑移起始尺寸完全吻合
    var leavingFromExpanded by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val refreshSpin = remember { Animatable(0f) }
    val tiltDamper = remember { PillTiltDamper() }

    BackHandler(enabled = isWebUi && expanded) { expanded = false }

    // 侧边形态与底部形态连续形变进度：0f=WebUI 侧边胶囊，1f=其余页面底部导航栏
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
            // 展开菜单后立刻释放拖拽状态，避免与手势拖动状态叠加
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

    // 仅停留在 WebUI 页时启用重力传感器阻尼切换；切换至底栏期间暂停采集，切回时保持离开前那一侧
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

    // 仅 WebUI 收起态静置 3 秒后柔和收敛前景透明度减少视觉打扰；毛玻璃背景层始终保持 100% 合成，绝不透出未模糊原图
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
        scope.launch {
            refreshSpin.snapTo(0f)
            refreshSpin.animateTo(360f, animationSpec = tween(durationMillis = 480))
        }
        onRefreshWebUi()
    }

    val statusBarTop = statusBarTopInset()
    // 仅把软键盘最终高度用于抬升侧边胶囊安全区；外层容器维持全屏坐标，确保消息平台/设置页弹出输入法时底栏纹丝不动
    @OptIn(ExperimentalLayoutApi::class)
    val imeBottomDp = with(density) { WindowInsets.imeAnimationTarget.getBottom(this).toDp().value }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxWidthDp = maxWidth.value
        val maxHeightDp = maxHeight.value

        // WebUI 展开时在背面铺一层全透明点击捕获区，不压暗网页，点击空白处立即收起（因此禁用按压水波纹）
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

        // 由单级无过冲临界阻尼弹簧直接驱动侧边锚点宽高，展开与收起保持单调连续过渡
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

        val expandedSideProgress by animateFloatAsState(
            targetValue = if (effectiveSideExpanded) 1f else 0f,
            animationSpec = tween(durationMillis = 160),
            label = "PortraitNavContentCrossfade"
        )

        val minTopDp = statusBarTop.value + 16f
        val sideMaxHeightDp = (maxHeightDp - imeBottomDp).coerceAtLeast(minTopDp + CollapsedPillHeightDp)
        val sideDockedTop = computeDockedPillTopDp(
            minTopDp = minTopDp,
            maxHeightDp = sideMaxHeightDp,
            bottomClearanceDp = PillBottomClearance.value,
            collapsedHeightDp = CollapsedPillHeight.value,
            currentHeightDp = sideTargetHeight.value,
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

        // 选中滑块在侧边纵向格与底栏横向格之间同步插值，保持统一阻尼弹性手感
        val selectedOrdinalFloat by animateFloatAsState(
            targetValue = selectedTab.ordinal.toFloat(),
            animationSpec = spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "HomeTabIndicatorOrdinal"
        )
        val clampedBottomProgress = bottomProgress.coerceIn(0f, 1f)
        val clampedExpandedProgress = expandedSideProgress.coerceIn(0f, 1f)
        val bottomTabBarWidth = computeBottomTabBarWidthDp(maxWidthDp)
        val bottomSingleTabWidth = (bottomTabBarWidth - PillInnerInsetDp * 2f) / HomeTab.entries.size
        val clampedSelectedOrdinal = selectedOrdinalFloat.coerceIn(0f, (HomeTab.entries.size - 1).toFloat())

        val indicatorX = lerpFloat(
            PillInnerInsetDp,
            PillInnerInsetDp + bottomSingleTabWidth * clampedSelectedOrdinal,
            clampedBottomProgress
        )
        val indicatorY = lerpFloat(
            PillInnerInsetDp + HomeTabButtonHeight.value * clampedSelectedOrdinal * clampedExpandedProgress,
            PillInnerInsetDp,
            clampedBottomProgress
        )
        val indicatorHeight = lerpFloat(
            lerpFloat(CollapsedNavButtonHeightDp, HomeTabButtonHeight.value, clampedExpandedProgress),
            HomeTabButtonHeight.value,
            clampedBottomProgress
        )

        val navLabelAlpha = ((1f - clampedExpandedProgress) * (1f - clampedBottomProgress * 2f)).coerceIn(0f, 1f)
        val collapsedRefreshAlpha = ((1f - clampedExpandedProgress) * (1f - clampedBottomProgress)).coerceIn(0f, 1f)
        val expandedExtraAlpha = (clampedExpandedProgress * (1f - clampedBottomProgress * 1.8f)).coerceIn(0f, 1f)
        val secondaryTabsAlpha = maxOf(clampedExpandedProgress, clampedBottomProgress)
        val pillShape = RoundedCornerShape(Corners.Pill)

        Box(
            modifier = Modifier
                .offset { IntOffset(frame.capsuleLeftDp.dp.roundToPx(), frame.capsuleTopDp.dp.roundToPx()) }
                .size(width = frame.capsuleWidthDp.dp, height = frame.capsuleHeightDp.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = pillShape,
                    clip = false,
                    ambientColor = GlassShadow,
                    spotColor = GlassShadow
                )
                .clip(pillShape)
                .border(1.dp, GlassStroke, pillShape)
                .pointerInput(isWebUi, expanded, maxWidthPx, minTopPx, maxTopCollapsedPx, sideWidthPx) {
                    // 仅处于 WebUI 收起态侧边胶囊时开放拖拽，展开态和底栏模式锁定位置，避免越界或误拖
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
                            // 手势指定新停靠侧后通知阻尼器锁定当前姿态，避免立刻被重力拉回对侧
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
            BackdropBlurView(
                blurTarget = blurTarget,
                blurRadius = NavigationBlurRadius,
                overlayColor = GlassSurface,
                modifier = Modifier.matchParentSize()
            )

            // 当前选中页签或收起态主导航的高光滑块，随形变过程连续滑向目标槽位
            Box(
                modifier = Modifier
                    .offset { IntOffset(indicatorX.dp.roundToPx(), indicatorY.dp.roundToPx()) }
                    .size(width = frame.tabWidthDp.dp, height = indicatorHeight.dp)
                    .graphicsLayer { alpha = foregroundIdleAlpha }
                    .clip(pillShape)
                    .background(GlassSelectedIndicator)
            )

            // 展开态下半区附加操作（细分隔线 + 刷新 + 终端）：切至底栏期间随退场平滑收束隐藏
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
                            height = ExpandedDividerSlotHeight + ExpandedActionButtonHeight * 2
                        )
                        .graphicsLayer { alpha = expandedExtraAlpha * foregroundIdleAlpha }
                )
            }

            // 收起态下方的一键「刷新」按钮，退场期间自动降低层级与透明度
            if (collapsedRefreshAlpha > 0.01f) {
                PillLabeledButton(
                    label = "刷新",
                    contentAlpha = 0.84f,
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
                            .size(22.dp)
                            .graphicsLayer { rotationZ = refreshSpin.value }
                    )
                }
            }

            // 消息平台与设置标签：随胶囊形变在纵向侧栏与横向底栏位置间扇形平滑移动
            if (secondaryTabsAlpha > 0.01f) {
                listOf(HomeTab.Platforms, HomeTab.Settings).forEach { tab ->
                    val pos = frame.tabPositionsDp[tab.ordinal]
                    Box(
                        modifier = Modifier
                            .zIndex(if (!isWebUi || expanded) 2f else 0f)
                            .offset { IntOffset(pos.first.dp.roundToPx(), pos.second.dp.roundToPx()) }
                            .size(width = frame.tabWidthDp.dp, height = HomeTabButtonHeight)
                            .graphicsLayer { alpha = secondaryTabsAlpha * foregroundIdleAlpha },
                        contentAlignment = Alignment.Center
                    ) {
                        HomeTabButton(
                            tab = tab,
                            selected = selectedTab == tab,
                            enabled = !isWebUi || expanded,
                            onClick = {
                                if (isWebUi) {
                                    leavingFromExpanded = true
                                    expanded = false
                                }
                                onTabSelected(tab)
                            },
                            modifier = Modifier
                                .wrapContentSize(align = Alignment.Center, unbounded = true)
                                .size(width = maxOf(frame.tabWidthDp, 66f).dp, height = HomeTabButtonHeight)
                        )
                    }
                }
            }

            // 首格常驻按钮：麦麦头像全程零跳动固定居中，文字在「导航」与「WebUI」间交叉渐显
            val primaryPos = frame.tabPositionsDp[HomeTab.WebUi.ordinal]
            val primaryContentAlpha = lerpFloat(
                1f,
                if (selectedTab == HomeTab.WebUi) 1f else 0.76f,
                clampedBottomProgress
            ) * foregroundIdleAlpha
            val primaryIconGap = lerpFloat(
                lerpFloat(3f, 4f, clampedExpandedProgress),
                4f,
                clampedBottomProgress
            ).dp
            Box(
                modifier = Modifier
                    .zIndex(3f)
                    .offset { IntOffset(primaryPos.first.dp.roundToPx(), primaryPos.second.dp.roundToPx()) }
                    .size(width = frame.tabWidthDp.dp, height = indicatorHeight.dp)
                    .clip(pillShape)
                    .clickable {
                        if (isWebUi) {
                            interactionTick++
                            expanded = !expanded
                        } else {
                            onTabSelected(HomeTab.WebUi)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.graphicsLayer { alpha = primaryContentAlpha }
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_maibot),
                        contentDescription = if (isWebUi && !expanded) "展开导航菜单" else HomeTab.WebUi.label,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(primaryIconGap))
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "导航",
                            color = TextPrimary,
                            fontSize = TextSizes.Caption,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.graphicsLayer { alpha = navLabelAlpha }
                        )
                        Text(
                            text = HomeTab.WebUi.label,
                            color = TextPrimary,
                            fontSize = TextSizes.Caption,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 12.sp,
                            maxLines = 1,
                            modifier = Modifier.graphicsLayer { alpha = 1f - navLabelAlpha }
                        )
                    }
                }
            }
        }

        // 右下角独立圆形终端按钮：切离 WebUI 时从侧边胶囊底部平滑分离就位，切回 WebUI 时顺畅融入胶囊
        if (frame.terminalAlpha > 0.02f) {
            HomeTerminalButton(
                blurTarget = blurTarget,
                onClick = onTerminalClick,
                enabled = !isWebUi,
                modifier = Modifier
                    .offset { IntOffset(frame.terminalLeftDp.dp.roundToPx(), frame.terminalTopDp.dp.roundToPx()) }
                    .size(PortraitBottomBarHeight)
                    .graphicsLayer {
                        alpha = frame.terminalAlpha
                        scaleX = frame.terminalScale
                        scaleY = frame.terminalScale
                    }
            )
        }
    }
}

/** 展开态下半区附加操作：细分隔线 + 全局「刷新」与「终端」入口。 */
@Composable
private fun ExpandedExtraActions(
    spinDegrees: Float,
    enabled: Boolean,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    .height(1.dp)
                    .background(CardDivider)
            )
        }

        PillLabeledButton(
            label = "刷新",
            contentAlpha = 0.82f,
            enabled = enabled,
            onClick = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .height(ExpandedActionButtonHeight)
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = "刷新 WebUI",
                tint = TextPrimary,
                modifier = Modifier
                    .size(22.dp)
                    .graphicsLayer { rotationZ = spinDegrees }
            )
        }

        PillLabeledButton(
            label = "终端",
            contentAlpha = 0.82f,
            enabled = enabled,
            onClick = onOpenTerminal,
            modifier = Modifier
                .fillMaxWidth()
                .height(ExpandedActionButtonHeight)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_terminal_code),
                contentDescription = "查看终端输出",
                tint = TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 胶囊内部通用“上图下文”动作按钮：对齐 HomeTabButton 的 Caption 粗体排布并统一裁剪圆角与点击域。 */
@Composable
private fun PillLabeledButton(
    label: String,
    contentAlpha: Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(Corners.Pill))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            icon()
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Bold,
                lineHeight = 12.sp,
                maxLines = 1
            )
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

