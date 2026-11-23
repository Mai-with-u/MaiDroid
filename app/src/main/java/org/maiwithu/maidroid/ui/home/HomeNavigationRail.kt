package org.maiwithu.maidroid.ui.home

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
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
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.ui.component.DampedDragAnimation
import org.maiwithu.maidroid.ui.component.InteractiveHighlight
import org.maiwithu.maidroid.ui.component.LiquidGlassIconButton
import org.maiwithu.maidroid.ui.component.computeLiquidPanelOffsetPx
import org.maiwithu.maidroid.ui.component.liquidGlassSurface
import org.maiwithu.maidroid.ui.component.rememberLiquidHaptics
import org.maiwithu.maidroid.ui.component.resolveLiquidHapticSlot
import org.maiwithu.maidroid.ui.theme.GlassFallbackSurface
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.LiquidBarSurface
import kotlin.math.abs
import kotlin.math.roundToInt

/** 侧栏里标签竖条的宽度（容得下“消息平台”“扩展组件”四个字）。 */
private val HomeNavigationRailBarWidth = 72.dp

/** 正常高度下的侧栏留白，用于计算页面正文左偏移与窄矮屏统一基准。 */
private val HomeNavigationRailPadding = 12.dp

/** 侧栏连同两侧留白的总宽度：页面内容左边让出这么多，静止时不被侧栏盖住。 */
internal val HomeNavigationRailWidth = HomeNavigationRailBarWidth + HomeNavigationRailPadding * 2

/** 矮屏（例如手机横屏约 360dp 高）下切换紧凑尺寸的高度分界线。 */
private const val CompactRailMaxHeightDp = 420f

/**
 * 横屏侧栏在不同可用高度下的几何尺寸（dp）：
 * 4 个页签 + 刷新按钮 + 终端按钮总高度若按常规 56dp 堆叠会超出手机横屏 360dp 高度，
 * 故矮屏自动切为紧凑尺寸确保零裁剪、零重叠。
 */
internal data class HomeRailMetrics(
    val compact: Boolean,
    val railPaddingDp: Float,
    val tabHeightDp: Float,
    val refreshButtonSizeDp: Float,
    val refreshBottomGapDp: Float,
    val terminalButtonSizeDp: Float
) {
    val tabRailHeightDp: Float
        get() = tabHeightDp * HomeTab.entries.size + 8f

    val minimumTotalHeightDp: Float
        get() = railPaddingDp * 2f + tabRailHeightDp + refreshButtonSizeDp + refreshBottomGapDp + terminalButtonSizeDp
}

/** 按当前容器高度计算横屏侧栏各元素尺寸。 */
internal fun computeHomeRailMetrics(maxHeightDp: Float): HomeRailMetrics =
    if (maxHeightDp < CompactRailMaxHeightDp) {
        HomeRailMetrics(
            compact = true,
            railPaddingDp = 8f,
            tabHeightDp = 46f,
            refreshButtonSizeDp = 38f,
            refreshBottomGapDp = 8f,
            terminalButtonSizeDp = 46f
        )
    } else {
        HomeRailMetrics(
            compact = false,
            railPaddingDp = HomeNavigationRailPadding.value,
            tabHeightDp = HomeTabButtonHeightDp,
            refreshButtonSizeDp = 44f,
            refreshBottomGapDp = 12f,
            terminalButtonSizeDp = HomeTabButtonHeightDp
        )
    }

/**
 * 主页导航放在哪：窗口宽大于高（手机横屏、平板横屏、横向分屏）时竖着放在左侧，否则悬浮在底部。
 * 手机是细长的，横过来高度只剩 360dp 左右，底栏和页面抢高度；放到左侧只占一窄列，页面能用满整块高度。
 */
internal fun homeUsesNavigationRail(widthDp: Float, heightDp: Float): Boolean = widthDp > heightDp

/**
 * 横屏时主页左侧的竖向液态玻璃导航：上面是四栏纵向可拖拽液态玻璃条，
 * 下面是圆形液态玻璃刷新（只在 WebUI 页签显示）与终端按钮。
 */
@Composable
internal fun HomeNavigationRail(
    selectedTab: HomeTab,
    backdrop: Backdrop,
    onTabSelected: (HomeTab) -> Unit,
    onTerminalClick: () -> Unit,
    onRefreshWebUi: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxHeight()) {
        val metrics = computeHomeRailMetrics(maxHeight.value)

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = HomeNavigationRailPadding, vertical = metrics.railPaddingDp.dp)
                .width(HomeNavigationRailBarWidth),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedHomeTabRail(
                selectedTab = selectedTab,
                backdrop = backdrop,
                compact = metrics.compact,
                onTabSelected = onTabSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.tabRailHeightDp.dp)
            )

            Spacer(modifier = Modifier.weight(1f))

            AnimatedVisibility(
                visible = onRefreshWebUi != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut()
            ) {
                RailRefreshButton(
                    backdrop = backdrop,
                    sizeDp = metrics.refreshButtonSizeDp,
                    onClick = { onRefreshWebUi?.invoke() },
                    modifier = Modifier.padding(bottom = metrics.refreshBottomGapDp.dp)
                )
            }

            HomeTerminalButton(
                backdrop = backdrop,
                onClick = onTerminalClick,
                size = metrics.terminalButtonSizeDp.dp,
                iconSizeDp = if (metrics.compact) 24f else 30f
            )
        }
    }
}

/** 纵向三层兄弟结构液态玻璃导航条：支持直接按住水滴上下拖动切换，按压膨胀并折射底部橙色放大图标。 */
@Composable
private fun AnimatedHomeTabRail(
    selectedTab: HomeTab,
    backdrop: Backdrop,
    compact: Boolean,
    onTabSelected: (HomeTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val animationScope = rememberCoroutineScope()
    val liquidHaptics = rememberLiquidHaptics()
    val tabsCount = HomeTab.entries.size
    val latestSelectedTab by rememberUpdatedState(selectedTab)
    val latestOnTabSelected by rememberUpdatedState(onTabSelected)
    val shape = { Capsule() }
    val tabsBackdrop = rememberLayerBackdrop()
    val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    BoxWithConstraints(modifier = modifier) {
        val innerPadding = 4.dp
        val tabHeight = (maxHeight - innerPadding * 2) / tabsCount
        val railHeightPx = with(density) { maxHeight.toPx() }.coerceAtLeast(1f)
        val tabHeightPx by rememberUpdatedState(with(density) { tabHeight.toPx() })
        val innerPaddingPx = with(density) { innerPadding.toPx() }
        val maxPanelOffsetPx = with(density) { 4f.dp.toPx() }

        val panelOffsetAnim = remember { Animatable(0f) }
        val panelOffsetPx by remember(railHeightPx, maxPanelOffsetPx) {
            derivedStateOf {
                computeLiquidPanelOffsetPx(
                    accumulatedOffsetPx = panelOffsetAnim.value,
                    containerSpanPx = railHeightPx,
                    maxPanelOffsetPx = maxPanelOffsetPx
                )
            }
        }

        var currentIndex by remember { mutableIntStateOf(selectedTab.ordinal) }
        var isRailDragging by remember { mutableStateOf(false) }
        var lastHapticIndex by remember { mutableIntStateOf(selectedTab.ordinal) }
        val railDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTab.ordinal.toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 70f / 56f,
                onDragStarted = { isRailDragging = true },
                onDragStopped = {
                    isRailDragging = false
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDragCancelled = {
                    isRailDragging = false
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    currentIndex = targetIndex
                    animateToValue(targetIndex.toFloat())
                    animationScope.launch {
                        panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    val step = dragAmount.y / tabHeightPx.coerceAtLeast(1f)
                    updateValue((targetValue + step).fastCoerceIn(0f, (tabsCount - 1).toFloat()))
                    animationScope.launch {
                        panelOffsetAnim.snapTo(panelOffsetAnim.value + dragAmount.y)
                    }
                }
            )
        }

        LaunchedEffect(selectedTab) {
            // 点按或拖拽落入新页签时若跨格尚未触发，补充一次侧栏落格触觉反馈
            if (lastHapticIndex != selectedTab.ordinal) {
                lastHapticIndex = selectedTab.ordinal
                liquidHaptics.performTabStep()
            }
            if (currentIndex != selectedTab.ordinal) {
                currentIndex = selectedTab.ordinal
            }
            if (abs(railDragAnimation.targetValue - selectedTab.ordinal.toFloat()) > 0.01f) {
                railDragAnimation.animateToValue(selectedTab.ordinal.toFloat())
            }
        }
        LaunchedEffect(railDragAnimation, liquidHaptics) {
            // 横屏侧栏上下拖动水滴跨过标签中线死区时触发一次刻度震动
            snapshotFlow { isRailDragging to railDragAnimation.value }
                .collect { (dragging, continuousValue) ->
                    if (!dragging) return@collect
                    val nextSlot = resolveLiquidHapticSlot(
                        currentSlot = lastHapticIndex,
                        continuousValue = continuousValue,
                        itemCount = tabsCount
                    )
                    if (nextSlot != lastHapticIndex) {
                        lastHapticIndex = nextSlot
                        liquidHaptics.performTabStep()
                    }
                }
        }
        LaunchedEffect(railDragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    val target = HomeTab.entries.getOrNull(index) ?: return@collectLatest
                    if (target != latestSelectedTab) {
                        latestOnTabSelected(target)
                    }
                }
        }

        val railHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    val slotH = (size.height / tabsCount).coerceAtLeast(1f)
                    Offset(size.width * 0.5f, (railDragAnimation.value + 0.5f) * slotH + panelOffsetPx)
                }
            )
        }

        val railPressScale = lerp(
            1f,
            1f + with(density) { 12f.dp.toPx() } / railHeightPx,
            railDragAnimation.pressProgress
        )

        // 兄弟层 1：基础液态玻璃竖栏与可点击页签
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationY = panelOffsetPx }
                .liquidGlassSurface(
                    backdrop = backdrop,
                    shape = shape,
                    blurRadius = 10.dp,
                    refractionHeight = 20.dp,
                    refractionAmount = 24.dp,
                    surfaceColor = LiquidBarSurface,
                    layerBlock = {
                        scaleX = railPressScale
                        scaleY = railPressScale
                    }
                )
                .then(railHighlight.modifier)
                .padding(innerPadding)
        ) {
            HomeTab.entries.forEach { tab ->
                HomeTabButton(
                    tab = tab,
                    selected = selectedTab == tab,
                    compact = compact,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }

        // 兄弟层 2：隐身品牌橙 1.18x 放大高亮层，供上方纵移水滴折射采样
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.18f, railDragAnimation.pressProgress)
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationY = panelOffsetPx }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = shape,
                        effects = {
                            val progress = railDragAnimation.pressProgress
                            vibrancy()
                            blur(10f.dp.toPx())
                            lens(20f.dp.toPx() * progress, 24f.dp.toPx() * progress)
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = railDragAnimation.pressProgress)
                        },
                        shadow = null,
                        layerBlock = {
                            scaleX = railPressScale
                            scaleY = railPressScale
                        },
                        onDrawSurface = {
                            drawRect(if (hasRenderEffect) LiquidBarSurface else GlassFallbackSurface)
                        }
                    )
                    .then(railHighlight.modifier)
                    .padding(innerPadding)
            ) {
                HomeTab.entries.forEach { tab ->
                    HomeTabButton(
                        tab = tab,
                        selected = true,
                        compact = compact,
                        accentHighlighted = true,
                        clickableEnabled = false,
                        onClick = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        // 兄弟层 3：独立游走纵向水滴滑块（不进竖栏裁剪区，按下自由膨胀并做纵向速度挤压）
        val fallbackBorder = if (hasRenderEffect) Modifier else Modifier.border(0.6.dp, GlassStroke, Capsule())
        Box(
            modifier = Modifier
                .padding(horizontal = innerPadding)
                .graphicsLayer {
                    translationY = innerPaddingPx + railDragAnimation.value * tabHeightPx + panelOffsetPx
                }
                .then(railHighlight.gestureModifier)
                .then(railDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = shape,
                    effects = {
                        val progress = railDragAnimation.pressProgress
                        lens(
                            refractionHeight = 10f.dp.toPx() * progress,
                            refractionAmount = 14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = railDragAnimation.pressProgress)
                    },
                    shadow = {
                        Shadow(alpha = railDragAnimation.pressProgress)
                    },
                    innerShadow = {
                        val progress = railDragAnimation.pressProgress
                        InnerShadow(radius = 8f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = railDragAnimation.scaleX
                        scaleY = railDragAnimation.scaleY
                        val velocity = railDragAnimation.velocity / 10f
                        scaleY /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleX *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = railDragAnimation.pressProgress
                        drawRect(GlassSelectedIndicator, alpha = 1f - progress)
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    }
                )
                .then(fallbackBorder)
                .fillMaxWidth()
                .height(tabHeight)
        )
    }
}

@Composable
private fun RailRefreshButton(
    backdrop: Backdrop,
    sizeDp: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    LiquidGlassIconButton(
        icon = Icons.Outlined.Refresh,
        contentDescription = "刷新 WebUI",
        onClick = onClick,
        backdrop = backdrop,
        size = sizeDp.dp,
        iconSize = if (sizeDp < 42f) 20.dp else 22.dp,
        modifier = modifier
    )
}
