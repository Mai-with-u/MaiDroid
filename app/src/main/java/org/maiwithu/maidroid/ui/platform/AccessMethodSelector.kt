package org.maiwithu.maidroid.ui.platform

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.ui.component.DampedDragAnimation
import org.maiwithu.maidroid.ui.component.InteractiveHighlight
import org.maiwithu.maidroid.ui.component.computeLiquidPanelOffsetPx
import org.maiwithu.maidroid.ui.component.rememberLiquidHaptics
import org.maiwithu.maidroid.ui.component.resolveLiquidBackdrop
import org.maiwithu.maidroid.ui.component.resolveLiquidHapticSlot
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.LocalMaiDroidColors
import org.maiwithu.maidroid.ui.theme.SegmentedThumbSurface
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.abs
import kotlin.math.roundToInt

private val AccessMethodSelectorItemWidth = 72.dp

private val AccessMethodSelectorItemSpacing = 2.dp

private val AccessMethodSelectorPadding = 2.dp

/**
 * 选择器按选项数定宽：每格 72dp 正好放下 12sp 的“官方机器人”五个字不折行。
 * 三个选项时约 224dp，卡片最窄 272dp 减去内边距还剩 240dp，放得下。
 */
internal fun accessMethodSelectorWidth(accessMethodCount: Int): Dp {
    val count = accessMethodCount.coerceAtLeast(1)
    return AccessMethodSelectorItemWidth * count.toFloat() +
        AccessMethodSelectorItemSpacing * (count - 1).toFloat() +
        AccessMethodSelectorPadding * 2f
}

/**
 * 液态玻璃分段接入方式选择器（三层兄弟结构）：
 * - 支持直接点击各档标签与按住水滴滑块在接入方式之间左右弹性滑动切换；
 * - 拖拽与点按触发滑块水滴膨胀（1.24x）、速度挤压拉伸及底层品牌橙 1.12x 放大文字的色散折射。
 */
@Composable
internal fun AccessMethodSelector(
    accessMethods: List<AccessMethod>,
    selectedAccessMethod: AccessMethod?,
    onAccessMethodSelected: (AccessMethod) -> Unit,
    modifier: Modifier = Modifier
) {
    if (accessMethods.isEmpty()) return

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val outerBackdrop = resolveLiquidBackdrop()
    val liquidHaptics = rememberLiquidHaptics()
    val itemCount = accessMethods.size
    val selectedIndex = accessMethods.indexOf(selectedAccessMethod).coerceIn(0, itemCount - 1)
    val latestMethods by rememberUpdatedState(accessMethods)
    val latestSelected by rememberUpdatedState(selectedAccessMethod)
    val latestOnSelected by rememberUpdatedState(onAccessMethodSelected)

    val itemSpacing = AccessMethodSelectorItemSpacing
    val horizontalPadding = AccessMethodSelectorPadding
    val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val itemWidth = ((maxWidth - horizontalPadding * 2f - itemSpacing * (itemCount - 1).coerceAtLeast(0)) /
            itemCount.toFloat()).coerceAtLeast(0.dp)
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val itemStepPx by rememberUpdatedState(with(density) { (itemWidth + itemSpacing).toPx() }.coerceAtLeast(1f))
        val trackWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val paddingPx = with(density) { horizontalPadding.toPx() }
        val maxPanelOffsetPx = with(density) { 3f.dp.toPx() }

        val panelOffsetAnim = remember { Animatable(0f) }
        val panelOffsetPx by remember(trackWidthPx, maxPanelOffsetPx) {
            derivedStateOf {
                computeLiquidPanelOffsetPx(
                    accumulatedOffsetPx = panelOffsetAnim.value,
                    containerSpanPx = trackWidthPx,
                    maxPanelOffsetPx = maxPanelOffsetPx
                )
            }
        }

        var currentIndex by remember { mutableIntStateOf(selectedIndex) }
        var isSelectorDragging by remember { mutableStateOf(false) }
        var lastHapticIndex by remember { mutableIntStateOf(selectedIndex) }
        val dragAnimation = remember(animationScope, itemCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(itemCount - 1).coerceAtLeast(1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.24f,
                onDragStarted = { isSelectorDragging = true },
                onDragStopped = {
                    isSelectorDragging = false
                    val targetIdx = targetValue.roundToInt().coerceIn(0, (itemCount - 1).coerceAtLeast(0))
                    currentIndex = targetIdx
                    animateToValue(targetIdx.toFloat())
                    animationScope.launch {
                        panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDragCancelled = {
                    isSelectorDragging = false
                    val targetIdx = targetValue.roundToInt().coerceIn(0, (itemCount - 1).coerceAtLeast(0))
                    currentIndex = targetIdx
                    animateToValue(targetIdx.toFloat())
                    animationScope.launch {
                        panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (itemCount <= 1) return@DampedDragAnimation
                    val step = dragAmount.x / itemStepPx * if (isLtr) 1f else -1f
                    updateValue((targetValue + step).fastCoerceIn(0f, (itemCount - 1).toFloat()))
                    animationScope.launch {
                        panelOffsetAnim.snapTo(panelOffsetAnim.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(selectedIndex, itemCount) {
            val safeTarget = selectedIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
            // 点按切换或外部变更选中接入方式时，若此前未因跨格震过则补触发一次触觉反馈
            if (lastHapticIndex != safeTarget) {
                lastHapticIndex = safeTarget
                liquidHaptics.performTabStep()
            }
            if (currentIndex != safeTarget) {
                currentIndex = safeTarget
            }
            if (abs(dragAnimation.targetValue - safeTarget.toFloat()) > 0.01f) {
                dragAnimation.animateToValue(safeTarget.toFloat())
            }
        }
        LaunchedEffect(dragAnimation, itemCount, liquidHaptics) {
            // 拖动滑块跨过格间中线死区瞬间触发一次轻震，避免停在分界附近抖动连震
            snapshotFlow { isSelectorDragging to dragAnimation.value }
                .collect { (dragging, continuousValue) ->
                    if (!dragging || itemCount <= 1) return@collect
                    val nextSlot = resolveLiquidHapticSlot(
                        currentSlot = lastHapticIndex,
                        continuousValue = continuousValue,
                        itemCount = itemCount
                    )
                    if (nextSlot != lastHapticIndex) {
                        lastHapticIndex = nextSlot
                        liquidHaptics.performTabStep()
                    }
                }
        }
        LaunchedEffect(dragAnimation) {
            snapshotFlow { currentIndex }
                .drop(1)
                .collectLatest { index ->
                    val targetMethod = latestMethods.getOrNull(index) ?: return@collectLatest
                    if (targetMethod != latestSelected) {
                        latestOnSelected(targetMethod)
                    }
                }
        }

        val isDark = LocalMaiDroidColors.current.isDark
        val selectorHighlight = remember(animationScope, isLtr, itemCount, isDark) {
            InteractiveHighlight(
                animationScope = animationScope,
                isDark = isDark,
                position = { size, _ ->
                    val slotW = (size.width / itemCount).coerceAtLeast(1f)
                    val cx = if (isLtr) {
                        (dragAnimation.value + 0.5f) * slotW + panelOffsetPx
                    } else {
                        size.width - (dragAnimation.value + 0.5f) * slotW + panelOffsetPx
                    }
                    Offset(cx, size.height * 0.5f)
                }
            )
        }

        val tabsBackdrop = rememberLayerBackdrop()
        val trackShape = RoundedCornerShape(Corners.Chip)

        // 兄弟层 1：底槽与可点击标签
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = panelOffsetPx }
                .clip(trackShape)
                .background(CardBorder)
                .then(selectorHighlight.modifier)
                .padding(horizontal = horizontalPadding, vertical = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            accessMethods.forEach { accessMethod ->
                val selected = accessMethod == selectedAccessMethod
                val textColor by animateColorAsState(
                    targetValue = if (selected) TextPrimary else TextMuted,
                    animationSpec = spring(
                        dampingRatio = 1f,
                        stiffness = Spring.StiffnessMediumLow
                    ),
                    label = "AccessMethodSelectorTextColor"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(Capsule())
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { onAccessMethodSelected(accessMethod) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = accessMethod.label,
                        color = textColor,
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }

        // 兄弟层 2：隐身品牌橙 1.12x 放大高亮层，供水滴滑块内部透视与色散折射
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffsetPx }
                .clip(trackShape)
                .background(SegmentedThumbSurface)
                .then(selectorHighlight.modifier)
                .padding(horizontal = horizontalPadding, vertical = horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val accentScale = lerp(1f, 1.12f, dragAnimation.pressProgress)
            accessMethods.forEach { accessMethod ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = accessMethod.label,
                        color = BrandOrange,
                        fontSize = TextSizes.Caption,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.graphicsLayer {
                            scaleX = accentScale
                            scaleY = accentScale
                        }
                    )
                }
            }
        }

        // 兄弟层 3：独立浮起的水滴分段滑块，按压可鼓出边框并跟随手势速度拉伸
        val fallbackBorder = if (hasRenderEffect) Modifier else Modifier.border(0.6.dp, GlassStroke, Capsule())
        val thumbBorder = if (!isDark) Modifier.border(0.5.dp, GlassStroke, Capsule()) else Modifier
        Box(
            modifier = Modifier
                .padding(vertical = horizontalPadding)
                .graphicsLayer {
                    translationX = if (isLtr) {
                        paddingPx + dragAnimation.value * itemStepPx + panelOffsetPx
                    } else {
                        trackWidthPx - paddingPx - itemWidthPx - dragAnimation.value * itemStepPx + panelOffsetPx
                    }
                }
                .then(if (itemCount > 1) selectorHighlight.gestureModifier else Modifier)
                .then(if (itemCount > 1) dragAnimation.modifier else Modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(outerBackdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dragAnimation.pressProgress
                        lens(
                            refractionHeight = 8f.dp.toPx() * progress,
                            refractionAmount = 12f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = 0.55f + 0.45f * dragAnimation.pressProgress)
                    },
                    shadow = {
                        if (isDark) {
                            Shadow(radius = 8.dp, alpha = 0.25f + 0.75f * dragAnimation.pressProgress)
                        } else {
                            Shadow(
                                radius = 8.dp,
                                color = Color.Black.copy(alpha = 0.12f + 0.10f * dragAnimation.pressProgress)
                            )
                        }
                    },
                    innerShadow = {
                        val progress = dragAnimation.pressProgress
                        InnerShadow(radius = 6f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dragAnimation.scaleX
                        scaleY = dragAnimation.scaleY
                        val velocity = dragAnimation.velocity / 12f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dragAnimation.pressProgress
                        // 兄弟层 2 已通过 tabsBackdrop 提供 SegmentedThumbSurface 底色与纯正品牌橙文字，
                        // 浅底给白胶囊保留低透明柔和透光以维持清晰轮廓
                        if (isDark) {
                            drawRect(Color.White.copy(alpha = 0.06f), alpha = 1f - progress)
                            drawRect(Color.Black.copy(alpha = 0.02f * progress))
                        } else {
                            drawRect(Color.White.copy(alpha = 0.14f), alpha = 1f - progress)
                            drawRect(Color.Black.copy(alpha = 0.03f * progress))
                        }
                    }
                )
                .then(thumbBorder)
                .then(fallbackBorder)
                .width(itemWidth)
                .fillMaxHeight()
        )
    }
}
