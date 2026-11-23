package org.maiwithu.maidroid.ui.component

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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardBorder
import org.maiwithu.maidroid.ui.theme.CardSurfaceSelected
import org.maiwithu.maidroid.ui.theme.GlassStroke
import org.maiwithu.maidroid.ui.theme.TextFaint
import org.maiwithu.maidroid.ui.theme.TextMuted
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import kotlin.math.abs
import kotlin.math.roundToInt

/** 分段液态玻璃选择器中的单个选项描述，支持纯单行标题或主副双行（如「正式版 / main」）。 */
internal data class LiquidSegmentItem(
    val label: String,
    val subtitle: String? = null
)

/** 将拖拽松手时的连续浮点位置收敛到合法选项下标。 */
internal fun resolveSegmentDragTarget(targetValue: Float, itemCount: Int): Int {
    if (itemCount <= 1) return 0
    return targetValue.roundToInt().coerceIn(0, itemCount - 1)
}

/**
 * 通用三层液态玻璃分段选择器（与消息平台接入方式选择器同源光学架构）：
 * - 外轨与内水滴滑块严格同心全圆 `Capsule()`，支持直接点选或按住水滴跨格弹性拖拽；
 * - 拖拽跨格实时触发线性马达轻震 + 色散折射下方 1.12x 放大的品牌橙文字；
 * - 若外部通过确认弹窗拦截变更（如切换 MaiBot 版本通道前需二次确认），取消后水滴会自动回弹至实际选中格。
 */
@Composable
internal fun LiquidSegmentedSelector(
    items: List<LiquidSegmentItem>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 44.dp
) {
    if (items.isEmpty()) return

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    val outerBackdrop = resolveLiquidBackdrop()
    val liquidHaptics = rememberLiquidHaptics()
    val itemCount = items.size
    val resolvedSelectedIndex = selectedIndex?.coerceIn(0, itemCount - 1)
    val initialFloatIndex = (resolvedSelectedIndex ?: 0).toFloat()

    val latestSelected by rememberUpdatedState(resolvedSelectedIndex)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestEnabled by rememberUpdatedState(enabled)

    val itemSpacing = 2.dp
    val innerPadding = 3.dp
    val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    BoxWithConstraints(
        modifier = modifier.height(height),
        contentAlignment = Alignment.CenterStart
    ) {
        val itemWidth = ((maxWidth - innerPadding * 2f - itemSpacing * (itemCount - 1).coerceAtLeast(0)) /
            itemCount.toFloat()).coerceAtLeast(0.dp)
        val itemWidthPx = with(density) { itemWidth.toPx() }
        val itemStepPx by rememberUpdatedState(with(density) { (itemWidth + itemSpacing).toPx() }.coerceAtLeast(1f))
        val trackWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
        val paddingPx = with(density) { innerPadding.toPx() }
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

        var isSelectorDragging by remember { mutableStateOf(false) }
        var lastHapticIndex by remember { mutableIntStateOf(resolvedSelectedIndex ?: 0) }
        var syncNonce by remember { mutableIntStateOf(0) }

        val dragAnimation = remember(animationScope, itemCount) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = initialFloatIndex,
                valueRange = 0f..(itemCount - 1).coerceAtLeast(1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.18f,
                onDragStarted = { isSelectorDragging = true },
                onDragStopped = {
                    isSelectorDragging = false
                    val targetIdx = resolveSegmentDragTarget(targetValue, itemCount)
                    val current = latestSelected
                    if (latestEnabled && current != null && targetIdx != current) {
                        latestOnSelect(targetIdx)
                    }
                    // 外部若弹出二次确认框且用户取消，依赖 syncNonce 推动滑块回弹到真实受控下标
                    syncNonce += 1
                    animationScope.launch {
                        panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDragCancelled = {
                    isSelectorDragging = false
                    syncNonce += 1
                    animationScope.launch {
                        panelOffsetAnim.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (!latestEnabled || itemCount <= 1) return@DampedDragAnimation
                    val step = dragAmount.x / itemStepPx * if (isLtr) 1f else -1f
                    updateValue((targetValue + step).fastCoerceIn(0f, (itemCount - 1).toFloat()))
                    animationScope.launch {
                        panelOffsetAnim.snapTo(panelOffsetAnim.value + dragAmount.x)
                    }
                }
            )
        }

        LaunchedEffect(resolvedSelectedIndex, syncNonce, itemCount) {
            val targetIdx = resolvedSelectedIndex ?: return@LaunchedEffect
            if (lastHapticIndex != targetIdx) {
                lastHapticIndex = targetIdx
                liquidHaptics.performTabStep()
            }
            if (abs(dragAnimation.targetValue - targetIdx.toFloat()) > 0.01f ||
                abs(dragAnimation.value - targetIdx.toFloat()) > 0.01f
            ) {
                dragAnimation.animateToValue(targetIdx.toFloat())
            }
        }

        LaunchedEffect(dragAnimation, itemCount, liquidHaptics) {
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

        val selectorHighlight = remember(animationScope, isLtr, itemCount) {
            InteractiveHighlight(
                animationScope = animationScope,
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
        val trackShape = Capsule()

        // ── 兄弟层 1：全圆 Capsule 槽底 + 选项交互文字 ─────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = panelOffsetPx }
                .clip(trackShape)
                .background(CardBorder.copy(alpha = 0.85f))
                .border(0.6.dp, GlassStroke, trackShape)
                .then(selectorHighlight.modifier)
                .padding(innerPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEachIndexed { index, item ->
                val selected = index == resolvedSelectedIndex
                val titleColor by animateColorAsState(
                    targetValue = when {
                        !enabled -> TextFaint
                        selected -> TextPrimary
                        else -> TextMuted
                    },
                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                    label = "LiquidSegmentTitleColor"
                )
                val subtitleColor by animateColorAsState(
                    targetValue = if (!enabled) TextFaint else if (selected) TextPrimary.copy(alpha = 0.78f) else TextMuted,
                    animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
                    label = "LiquidSegmentSubtitleColor"
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(trackShape)
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            enabled = enabled,
                            role = Role.RadioButton,
                            onClick = { onSelect(index) }
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = item.label,
                        color = titleColor,
                        fontSize = if (item.subtitle != null) TextSizes.Body else TextSizes.Caption,
                        fontWeight = FontWeight.Bold,
                        lineHeight = if (item.subtitle != null) 16.sp else 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.subtitle.isNullOrBlank()) {
                        Text(
                            text = item.subtitle,
                            color = subtitleColor,
                            fontSize = TextSizes.Caption,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ── 兄弟层 2：隐身品牌橙 1.12x 放大高亮层，供水滴滑块实时折射 ──────────────────────
        Row(
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .alpha(0f)
                .layerBackdrop(tabsBackdrop)
                .graphicsLayer { translationX = panelOffsetPx }
                .clip(trackShape)
                .background(CardSurfaceSelected)
                .then(selectorHighlight.modifier)
                .padding(innerPadding),
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val accentScale = lerp(1f, 1.12f, dragAnimation.pressProgress)
            items.forEach { item ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .graphicsLayer {
                            scaleX = accentScale
                            scaleY = accentScale
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = item.label,
                        color = BrandOrange,
                        fontSize = if (item.subtitle != null) TextSizes.Body else TextSizes.Caption,
                        fontWeight = FontWeight.Bold,
                        lineHeight = if (item.subtitle != null) 16.sp else 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!item.subtitle.isNullOrBlank()) {
                        Text(
                            text = item.subtitle,
                            color = BrandOrange.copy(alpha = 0.82f),
                            fontSize = TextSizes.Caption,
                            lineHeight = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        // ── 兄弟层 3：独立浮起液态水滴滑块（仅存在明确选中格时显现）──────────────────────────
        if (resolvedSelectedIndex != null) {
            val fallbackBorder = if (hasRenderEffect) Modifier else Modifier.border(0.6.dp, GlassStroke, trackShape)
            val allowDrag = enabled && itemCount > 1
            Box(
                modifier = Modifier
                    .padding(vertical = innerPadding)
                    .graphicsLayer {
                        translationX = if (isLtr) {
                            paddingPx + dragAnimation.value * itemStepPx + panelOffsetPx
                        } else {
                            trackWidthPx - paddingPx - itemWidthPx - dragAnimation.value * itemStepPx + panelOffsetPx
                        }
                    }
                    .then(if (allowDrag) selectorHighlight.gestureModifier else Modifier)
                    .then(if (allowDrag) dragAnimation.modifier else Modifier)
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
                            Shadow(radius = 8.dp, alpha = 0.25f + 0.75f * dragAnimation.pressProgress)
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
                            // 复用底层 CardSurfaceSelected 与品牌橙亮色，仅保留微弱玻璃透光反光防文字压暗
                            drawRect(Color.White.copy(alpha = 0.06f), alpha = 1f - progress)
                            drawRect(Color.Black.copy(alpha = 0.02f * progress))
                        }
                    )
                    .then(fallbackBorder)
                    .width(itemWidth)
                    .fillMaxHeight()
            )
        }
    }
}
