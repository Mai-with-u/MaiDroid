package org.maiwithu.maidroid.ui.home

import android.content.Context
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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.graphics.Color
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
import org.maiwithu.maidroid.ui.component.BackdropBlurView
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.CardDivider
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes

private const val PillPrefsName = "maidroid_ui_prefs"
private const val PrefKeyDockRight = "webui_pill_dock_right_v2"
private const val PrefKeyVerticalBias = "webui_pill_vertical_bias"

/** 竖屏初次进入 WebUI 时悬浮胶囊默认停靠在左侧（false），符合右手滑屏时左手拇指/视觉习惯。 */
internal const val DefaultDockRight = false

private const val DefaultVerticalBias = 0.65f
private const val PillIdleFadeDelayMs = 3_000L

internal const val CollapsedPillWidthDp = 64f
internal const val CollapsedPillHeightDp = 118f
internal const val ExpandedPillWidthDp = 74f
internal const val ExpandedPillHeightDp = 293f

private val CollapsedPillWidth = CollapsedPillWidthDp.dp
private val CollapsedPillHeight = CollapsedPillHeightDp.dp
private val ExpandedPillWidth = ExpandedPillWidthDp.dp
private val ExpandedPillHeight = ExpandedPillHeightDp.dp

private val PillOuterPadding = 4.dp
private val PillButtonGap = 4.dp
private val CollapsedExpandButtonHeight = 54.dp
private val CollapsedRefreshButtonHeight = 52.dp
private val ExpandedDividerSlotHeight = 9.dp
private val ExpandedActionButtonHeight = 54.dp
private val PillEdgeMargin = 10.dp
private val PillBottomClearance = 84.dp

/** 限制胶囊垂直停靠比例在 `[0f, 1f]` 安全区内。 */
internal fun clampPillVerticalBias(bias: Float): Float = bias.coerceIn(0f, 1f)

/** 松手后根据胶囊中心横坐标与容器宽度中线比较，决定自动吸附到左侧还是右侧。 */
internal fun shouldDockPillRight(centerX: Float, containerWidth: Float): Boolean =
    centerX >= containerWidth * 0.5f

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
 * WebUI 竖屏专属侧边灵动导航胶囊：
 * - 收起态放大至更易点击的 64×118dp，视觉完全对齐底栏与横屏侧栏毛玻璃（麦麦头像 +「导航」与一键「刷新」）；
 * - 展开态直接在原处平滑扩展为横屏侧栏同款竖排导航（WebUI / 消息平台 / 设置 + 细分隔线 + 刷新 / 终端）；
 * - 默认停靠屏幕左侧，并结合重力传感器做低通滤波、持续驻留与冷却三级阻尼切换（平放保持原位），同时支持全向拖拽。
 */
@Composable
internal fun WebUiFloatingPill(
    blurTarget: BlurTarget?,
    onRefreshWebUi: () -> Unit,
    onSelectTab: (HomeTab) -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        context.getSharedPreferences(PillPrefsName, Context.MODE_PRIVATE)
    }

    var dockRight by rememberSaveable {
        mutableStateOf(prefs.getBoolean(PrefKeyDockRight, DefaultDockRight))
    }
    var verticalBias by rememberSaveable {
        mutableFloatStateOf(clampPillVerticalBias(prefs.getFloat(PrefKeyVerticalBias, DefaultVerticalBias)))
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val refreshSpin = remember { Animatable(0f) }
    val tiltDamper = remember { PillTiltDamper() }

    BackHandler(enabled = expanded) { expanded = false }

    // 绑定手机倾斜传感器：平放时维持当前边，持续左/右倾经过阻尼积分后再丝滑换边并持久记忆
    BindPillTiltSensor(
        currentDockRight = dockRight,
        interactionPaused = expanded || isDragging,
        damper = tiltDamper,
        onDockSideChanged = { nextDockRight ->
            dockRight = nextDockRight
            interactionTick++
            prefs.edit()
                .putBoolean(PrefKeyDockRight, nextDockRight)
                .apply()
        }
    )

    // 静置 3 秒后柔和降低透明度减少遮挡感；拖动、展开、点按或换边瞬间立刻恢复完全不透明
    var idleDimmed by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, isDragging, interactionTick) {
        idleDimmed = false
        if (!expanded && !isDragging) {
            delay(PillIdleFadeDelayMs)
            idleDimmed = true
        }
    }
    val pillAlpha by animateFloatAsState(
        targetValue = if (idleDimmed) 0.76f else 1f,
        animationSpec = tween(durationMillis = 360),
        label = "WebUiPillAlpha"
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
    // 弹出软键盘时跟着把胶囊可用区域抬到键盘上方，避免聊天页打字时胶囊被键盘拦腰遮住
    @OptIn(ExperimentalLayoutApi::class)
    val imeBottomInset = WindowInsets.imeAnimationTarget.only(WindowInsetsSides.Bottom)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(imeBottomInset)
    ) {
        // 展开时在背面铺一层全透明点击捕获区，不压暗网页，点击空白处立即收起
        if (expanded) {
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

        val targetWidth = if (expanded) ExpandedPillWidth else CollapsedPillWidth
        val targetHeight = if (expanded) ExpandedPillHeight else CollapsedPillHeight

        // 用显式无过冲临界阻尼弹簧直接驱动外框宽高：从 293dp 单调缩回 118dp，彻底根治旧版关闭瞬间叠加变长的缺陷
        val animatedWidth by animateDpAsState(
            targetValue = targetWidth,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "WebUiPillWidth"
        )
        val animatedHeight by animateDpAsState(
            targetValue = targetHeight,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMedium
            ),
            label = "WebUiPillHeight"
        )

        // 交叉淡入淡出进度：让收起与展开两套固定尺寸内容在稳定版式内渐变交替，避免文字在过渡宽度里被挤换行
        val expandedProgress by animateFloatAsState(
            targetValue = if (expanded) 1f else 0f,
            animationSpec = tween(durationMillis = 160),
            label = "WebUiPillContentCrossfade"
        )

        val minTop = statusBarTop + 16.dp
        val dockedTop = computeDockedPillTopDp(
            minTopDp = minTop.value,
            maxHeightDp = maxHeight.value,
            bottomClearanceDp = PillBottomClearance.value,
            collapsedHeightDp = CollapsedPillHeight.value,
            currentHeightDp = targetHeight.value,
            verticalBias = verticalBias
        ).dp
        val dockedLeft = if (dockRight) maxWidth - targetWidth - PillEdgeMargin else PillEdgeMargin

        val maxWidthPx = with(density) { maxWidth.toPx() }
        val minTopPx = with(density) { minTop.toPx() }
        val maxTopCollapsedPx = with(density) {
            (maxHeight - CollapsedPillHeight - PillBottomClearance).coerceAtLeast(minTop).toPx()
        }
        val pillWidthPx = with(density) { targetWidth.toPx() }

        var dragLeftPx by remember { mutableFloatStateOf(0f) }
        var dragTopPx by remember { mutableFloatStateOf(0f) }
        val latestDockedLeftPx by rememberUpdatedState(with(density) { dockedLeft.toPx() })
        val latestDockedTopPx by rememberUpdatedState(with(density) { dockedTop.toPx() })

        val targetLeftDp = if (isDragging) with(density) { dragLeftPx.toDp() } else dockedLeft
        val targetTopDp = if (isDragging) with(density) { dragTopPx.toDp() } else dockedTop

        // 拖动时零延迟跟手；松手吸附及传感器自动换边采用柔和偏低刚度阻尼弹簧，视觉过渡温润自然不生硬
        val animatedLeft by animateDpAsState(
            targetValue = targetLeftDp,
            animationSpec = if (isDragging) snap() else spring(
                dampingRatio = 0.86f,
                stiffness = 260f
            ),
            label = "WebUiPillX"
        )
        val animatedTop by animateDpAsState(
            targetValue = targetTopDp,
            animationSpec = if (isDragging) snap() else spring(
                dampingRatio = 0.86f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "WebUiPillY"
        )

        val pillShape = RoundedCornerShape(Corners.Pill)

        Box(
            modifier = Modifier
                .offset { IntOffset(animatedLeft.roundToPx(), animatedTop.roundToPx()) }
                .size(width = animatedWidth, height = animatedHeight)
                .graphicsLayer { alpha = pillAlpha }
                .shadow(
                    elevation = 18.dp,
                    shape = pillShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.36f),
                    spotColor = Color.Black.copy(alpha = 0.36f)
                )
                .clip(pillShape)
                .border(1.dp, GlassStroke, pillShape)
                .pointerInput(maxWidthPx, minTopPx, maxTopCollapsedPx, pillWidthPx) {
                    detectDragGestures(
                        onDragStart = {
                            dragLeftPx = latestDockedLeftPx
                            dragTopPx = latestDockedTopPx
                            isDragging = true
                            interactionTick++
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val maxLeft = (maxWidthPx - pillWidthPx - 4f).coerceAtLeast(4f)
                            dragLeftPx = (dragLeftPx + dragAmount.x).coerceIn(4f, maxLeft)
                            dragTopPx = (dragTopPx + dragAmount.y).coerceIn(minTopPx, maxTopCollapsedPx)
                        },
                        onDragEnd = {
                            val centerX = dragLeftPx + pillWidthPx * 0.5f
                            dockRight = shouldDockPillRight(centerX, maxWidthPx)
                            val span = (maxTopCollapsedPx - minTopPx).coerceAtLeast(1f)
                            verticalBias = clampPillVerticalBias((dragTopPx - minTopPx) / span)
                            prefs.edit()
                                .putBoolean(PrefKeyDockRight, dockRight)
                                .putFloat(PrefKeyVerticalBias, verticalBias)
                                .apply()
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

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                // 收起态固定版式层：!expanded 时提升 zIndex 在最顶层响应点击，退场期降到底层不拦截新展开菜单的点击
                if (expandedProgress < 0.99f) {
                    CollapsedPillContent(
                        spinDegrees = refreshSpin.value,
                        clickableEnabled = !expanded,
                        onExpand = {
                            interactionTick++
                            expanded = true
                        },
                        onRefresh = { triggerRefresh() },
                        modifier = Modifier
                            .zIndex(if (expanded) 0f else 1f)
                            .wrapContentSize(align = Alignment.TopCenter, unbounded = true)
                            .size(width = CollapsedPillWidth, height = CollapsedPillHeight)
                            .graphicsLayer { alpha = 1f - expandedProgress }
                    )
                }

                // 展开态固定版式层：复刻横屏侧栏竖排页签与辅助动作，expanded 时置于最顶层响应点击
                if (expandedProgress > 0.01f) {
                    ExpandedPillContent(
                        spinDegrees = refreshSpin.value,
                        onSelectTab = { tab ->
                            expanded = false
                            if (tab != HomeTab.WebUi) {
                                onSelectTab(tab)
                            }
                        },
                        onRefresh = {
                            triggerRefresh()
                            expanded = false
                        },
                        onOpenTerminal = {
                            expanded = false
                            onOpenTerminal()
                        },
                        modifier = Modifier
                            .zIndex(if (expanded) 1f else 0f)
                            .wrapContentSize(align = Alignment.TopCenter, unbounded = true)
                            .size(width = ExpandedPillWidth, height = ExpandedPillHeight)
                            .graphicsLayer { alpha = expandedProgress }
                    )
                }
            }
        }
    }
}

/** 收起态两颗大触控圆角钮：上方麦麦头像+「导航」展开展开菜单，下方一键「刷新」。 */
@Composable
private fun CollapsedPillContent(
    spinDegrees: Float,
    clickableEnabled: Boolean,
    onExpand: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemShape = RoundedCornerShape(Corners.Pill)
    Column(
        modifier = modifier.padding(PillOuterPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        PillLabeledButton(
            label = "导航",
            contentAlpha = 1f,
            enabled = clickableEnabled,
            onClick = onExpand,
            modifier = Modifier
                .fillMaxWidth()
                .height(CollapsedExpandButtonHeight)
                .clip(itemShape)
                .background(GlassSelectedIndicator)
        ) {
            Image(
                painter = painterResource(R.drawable.ic_maibot),
                contentDescription = "展开导航菜单",
                modifier = Modifier.size(24.dp)
            )
        }

        Spacer(modifier = Modifier.height(PillButtonGap))

        PillLabeledButton(
            label = "刷新",
            contentAlpha = 0.84f,
            enabled = clickableEnabled,
            onClick = onRefresh,
            modifier = Modifier
                .fillMaxWidth()
                .height(CollapsedRefreshButtonHeight)
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
    }
}

/** 展开态横屏侧栏同款竖排胶囊：三颗统一页签钮（当前 WebUI 高亮） + 细分隔线 + 刷新与终端。 */
@Composable
private fun ExpandedPillContent(
    spinDegrees: Float,
    onSelectTab: (HomeTab) -> Unit,
    onRefresh: () -> Unit,
    onOpenTerminal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val selectedShape = RoundedCornerShape(Corners.Pill)
    Column(
        modifier = modifier.padding(PillOuterPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HomeTab.entries.forEach { tab ->
            val isSelected = tab == HomeTab.WebUi
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HomeTabButtonHeight)
                    .then(
                        if (isSelected) {
                            Modifier
                                .clip(selectedShape)
                                .background(GlassSelectedIndicator)
                        } else {
                            Modifier
                        }
                    )
            ) {
                HomeTabButton(
                    tab = tab,
                    selected = isSelected,
                    onClick = { onSelectTab(tab) },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

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
            enabled = true,
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
            enabled = true,
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
