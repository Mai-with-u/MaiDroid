package org.maiwithu.maidroid.ui.home

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import eightbitlab.com.blurview.BlurTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.ui.component.BackdropBlurView
import org.maiwithu.maidroid.ui.component.FlatFeatureIcons
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.CardSurfaceSelected
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassSelectedIndicator
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.GlassSurface
import org.maiwithu.maidroid.ui.theme.IconFreshGreen
import org.maiwithu.maidroid.ui.theme.IconIrisBlue
import org.maiwithu.maidroid.ui.theme.IconPeachOrange
import org.maiwithu.maidroid.ui.theme.IconSkyBlue
import org.maiwithu.maidroid.ui.theme.TextPrimary

private const val PillPrefsName = "maidroid_ui_prefs"
private const val PrefKeyDockRight = "webui_pill_dock_right"
private const val PrefKeyVerticalBias = "webui_pill_vertical_bias"
private const val DefaultVerticalBias = 0.72f
private const val PillIdleFadeDelayMs = 3_000L

private val PillWidth = 46.dp
private val PillButtonSize = 36.dp
private val PillInnerPadding = 5.dp
private val PillButtonGap = 6.dp
private val PillEdgeMargin = 10.dp
private val PillBottomClearance = 84.dp
private val CollapsedPillHeight = PillInnerPadding * 2 + PillButtonSize * 2 + PillButtonGap
private val ExpandedPillHeight = PillInnerPadding * 2 + PillButtonSize * 5 + PillButtonGap * 4

/** 限制胶囊垂直停靠比例在 `[0f, 1f]` 安全区内。 */
internal fun clampPillVerticalBias(bias: Float): Float = bias.coerceIn(0f, 1f)

/** 松手后根据胶囊中心横坐标与容器宽度中线比较，决定自动吸附到左侧还是右侧。 */
internal fun shouldDockPillRight(centerX: Float, containerWidth: Float): Boolean =
    centerX >= containerWidth * 0.5f

/**
 * WebUI 竖屏专属侧边灵动胶囊：
 * - 收起态仅占极窄双圆钮（上方一键刷新、下方原地展开切换入口），不挡网页右上角控件也不遮底部；
 * - 展开态直接在胶囊内弹性伸长展示 5 颗快捷圆钮（刷新 / 消息平台 / 设置 / 终端 / 收起），点击网页任意处收起；
 * - 支持全向按住拖动并自动弹簧吸附到左或右边缘，记忆停靠位置，静置 3 秒后柔和半透明隐身。
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
        mutableStateOf(prefs.getBoolean(PrefKeyDockRight, true))
    }
    var verticalBias by rememberSaveable {
        mutableFloatStateOf(clampPillVerticalBias(prefs.getFloat(PrefKeyVerticalBias, DefaultVerticalBias)))
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var interactionTick by remember { mutableIntStateOf(0) }
    val refreshSpin = remember { Animatable(0f) }

    BackHandler(enabled = expanded) { expanded = false }

    // 静置 3 秒后自动轻度降透明减少视觉打扰；拖动、展开或点按瞬间立刻回到完全不透明
    var idleDimmed by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, isDragging, interactionTick) {
        idleDimmed = false
        if (!expanded && !isDragging) {
            delay(PillIdleFadeDelayMs)
            idleDimmed = true
        }
    }
    val pillAlpha by animateFloatAsState(
        targetValue = if (idleDimmed) 0.72f else 1f,
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
        // 展开时在背面铺一层全透明点击捕获区，不压暗网页，点击空白处或按返回键立即收起
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

        val currentHeight = if (expanded) ExpandedPillHeight else CollapsedPillHeight
        val minTop = statusBarTop + 16.dp
        val maxTop = (maxHeight - currentHeight - PillBottomClearance).coerceAtLeast(minTop)
        val dockedTop = minTop + (maxTop - minTop) * verticalBias
        val dockedLeft = if (dockRight) maxWidth - PillWidth - PillEdgeMargin else PillEdgeMargin

        val maxWidthPx = with(density) { maxWidth.toPx() }
        val minTopPx = with(density) { minTop.toPx() }
        val maxTopCollapsedPx = with(density) {
            (maxHeight - CollapsedPillHeight - PillBottomClearance).coerceAtLeast(minTop).toPx()
        }
        val pillWidthPx = with(density) { PillWidth.toPx() }

        var dragLeftPx by remember { mutableFloatStateOf(0f) }
        var dragTopPx by remember { mutableFloatStateOf(0f) }
        val latestDockedLeftPx by rememberUpdatedState(with(density) { dockedLeft.toPx() })
        val latestDockedTopPx by rememberUpdatedState(with(density) { dockedTop.toPx() })

        val targetLeftDp = if (isDragging) with(density) { dragLeftPx.toDp() } else dockedLeft
        val targetTopDp = if (isDragging) with(density) { dragTopPx.toDp() } else dockedTop

        // 拖动时用 snap() 做到零延迟跟手；松手时用弹性弹簧丝滑吸附至屏幕左侧或右侧
        val animatedLeft by animateDpAsState(
            targetValue = targetLeftDp,
            animationSpec = if (isDragging) snap() else spring(
                dampingRatio = 0.78f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "WebUiPillX"
        )
        val animatedTop by animateDpAsState(
            targetValue = targetTopDp,
            animationSpec = if (isDragging) snap() else spring(
                dampingRatio = 0.84f,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "WebUiPillY"
        )

        val pillShape = RoundedCornerShape(Corners.Pill)

        Box(
            modifier = Modifier
                .offset { IntOffset(animatedLeft.roundToPx(), animatedTop.roundToPx()) }
                .width(PillWidth)
                .graphicsLayer { alpha = pillAlpha }
                .shadow(
                    elevation = 16.dp,
                    shape = pillShape,
                    clip = false,
                    ambientColor = Color.Black.copy(alpha = 0.36f),
                    spotColor = Color.Black.copy(alpha = 0.36f)
                )
                .clip(pillShape)
                .border(1.dp, GlassStroke, pillShape)
                .pointerInput(maxWidthPx, minTopPx, maxTopCollapsedPx) {
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

            Column(
                modifier = Modifier
                    .padding(PillInnerPadding)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = 0.82f,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(PillButtonGap)
            ) {
                // 首位固定为一键刷新，收起与展开状态下位置稳定，无需展开即可直接刷新网页
                PillCircleIconButton(
                    icon = Icons.Outlined.Refresh,
                    tint = TextPrimary,
                    backgroundColor = IconSkyBlue,
                    contentDescription = "刷新 WebUI",
                    iconModifier = Modifier.graphicsLayer { rotationZ = refreshSpin.value },
                    onClick = {
                        triggerRefresh()
                        if (expanded) expanded = false
                    }
                )

                // 收起态第二颗钮为展开入口；展开态将其平滑切换为功能菜单按钮组
                if (!expanded) {
                    PillCircleIconButton(
                        icon = FlatFeatureIcons.GridMenu,
                        tint = Color.Unspecified,
                        backgroundColor = IconPeachOrange,
                        contentDescription = "展开导航菜单",
                        onClick = {
                            interactionTick++
                            expanded = true
                        }
                    )
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(180)) + scaleIn(initialScale = 0.8f),
                    exit = fadeOut(tween(140)) + scaleOut(targetScale = 0.8f)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(PillButtonGap)
                    ) {
                        PillCircleIconButton(
                            icon = FlatFeatureIcons.Platforms,
                            tint = Color.Unspecified,
                            backgroundColor = IconIrisBlue,
                            contentDescription = HomeTab.Platforms.label,
                            onClick = {
                                expanded = false
                                onSelectTab(HomeTab.Platforms)
                            }
                        )

                        PillCircleIconButton(
                            icon = FlatFeatureIcons.SettingsGear,
                            tint = Color.Unspecified,
                            backgroundColor = IconFreshGreen,
                            contentDescription = HomeTab.Settings.label,
                            onClick = {
                                expanded = false
                                onSelectTab(HomeTab.Settings)
                            }
                        )

                        PillCirclePainterButton(
                            drawableRes = R.drawable.ic_terminal_code,
                            backgroundColor = CardSurfaceSelected,
                            contentDescription = "查看终端输出",
                            onClick = {
                                expanded = false
                                onOpenTerminal()
                            }
                        )

                        PillCircleIconButton(
                            icon = Icons.Outlined.Close,
                            tint = TextPrimary,
                            backgroundColor = GlassSelectedIndicator,
                            contentDescription = "收起导航菜单",
                            onClick = {
                                interactionTick++
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PillCircleIconButton(
    icon: ImageVector,
    tint: Color,
    backgroundColor: Color,
    contentDescription: String,
    onClick: () -> Unit,
    iconModifier: Modifier = Modifier
) {
    Box(
        modifier = Modifier
            .size(PillButtonSize)
            .clip(CircleShape)
            .background(backgroundColor)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = iconModifier.size(21.dp)
        )
    }
}

@Composable
private fun PillCirclePainterButton(
    drawableRes: Int,
    backgroundColor: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(PillButtonSize)
            .clip(CircleShape)
            .background(backgroundColor)
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}
