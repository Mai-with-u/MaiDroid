package org.maiwithu.maidroid.ui.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import org.maiwithu.maidroid.ui.theme.BrandOrange
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.LocalMaiDroidColors
import org.maiwithu.maidroid.ui.theme.SwitchTrackUnchecked
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 计算液态玻璃滑块胶囊在轨道内的水平位移（px）：
 * 保证左端对齐 0、右端对齐 `trackWidthPx - thumbWidthPx`，高光水滴不会冲出轨道或留出橙色填充溢边。
 */
internal fun computeLiquidSliderThumbOffsetPx(
    progress: Float,
    trackWidthPx: Float,
    thumbWidthPx: Float,
    isLtr: Boolean = true
): Float {
    val clampedProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
    val travelPx = (trackWidthPx - thumbWidthPx).coerceAtLeast(0f)
    val offset = travelPx * clampedProgress
    return if (isLtr) offset else -offset
}

/**
 * 将用户在轨道上的点击或起始横拖坐标连续映射为区间内的浮点值：
 * 以滑块中心的可移动范围 `[thumbWidth/2, trackWidth - thumbWidth/2]` 为基准对齐手感。
 */
internal fun computeLiquidSliderValueForPosition(
    positionX: Float,
    trackWidthPx: Float,
    thumbWidthPx: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    isLtr: Boolean = true
): Float {
    val start = valueRange.start
    val end = valueRange.endInclusive
    if (end <= start) return start
    val halfThumb = (thumbWidthPx * 0.5f).coerceAtLeast(0f)
    val travelPx = (trackWidthPx - thumbWidthPx).coerceAtLeast(0f)
    val rawFraction = if (travelPx <= 0f) {
        if (trackWidthPx > 0f && positionX.isFinite()) (positionX / trackWidthPx).coerceIn(0f, 1f) else 0f
    } else {
        val safeX = if (positionX.isFinite()) positionX else halfThumb
        ((safeX - halfThumb) / travelPx).coerceIn(0f, 1f)
    }
    val directedFraction = if (isLtr) rawFraction else 1f - rawFraction
    return (start + (end - start) * directedFraction).coerceIn(start, end)
}

/**
 * 计算连续滑动过程中带滞回防抖的刻度槽位：
 * 当 `tickStep > 0` 时沿用 [resolveLiquidHapticSlot] 在相邻刻度中点外保留死区，
 * 让连续丝滑拖动的滑块仍能稳定敲出每隔 `tickStep` 一次的清晰触觉反馈。
 */
internal fun resolveLiquidSliderHapticTick(
    currentTick: Int,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    tickStep: Float
): Int {
    val span = valueRange.endInclusive - valueRange.start
    if (tickStep <= 0f || span <= 0f || !value.isFinite()) return 0
    val stepCount = (span / tickStep).roundToInt().coerceAtLeast(1)
    val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
    val continuousSlot = ((clamped - valueRange.start) / span).coerceIn(0f, 1f) * stepCount
    if (currentTick < 0) {
        return continuousSlot.roundToInt().coerceIn(0, stepCount)
    }
    return resolveLiquidHapticSlot(
        currentSlot = currentTick,
        continuousValue = continuousSlot,
        itemCount = stepCount + 1
    )
}

/**
 * 连续阻尼液态玻璃滑块（基于 Kyant0 LiquidSlider 物理模型，适配纵向列表、连续浮点无顿挫与刻度触觉反馈）：
 * - 拖动过程中内部维持连续 `rawDragValue`，不被外部四舍五入回传拉扯产生顿挫；
 * - 跨越指定 `hapticStep` 刻度或轻点轨道直达时触发线性/转子马达清脆震动；
 * - 按下后滑块膨胀至 1.45x 并通过双 Backdrop 折射下方橙/灰填充轨道，Android 8–11 自动保留柔白磨砂与描边质感。
 */
@Composable
internal fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    backdrop: Backdrop = resolveLiquidBackdrop(CardSurface),
    enabled: Boolean = true,
    hapticStep: Float = 0f,
    accentColor: Color = BrandOrange,
    trackColor: Color = SwitchTrackUnchecked
) {
    val trackHeight = 8.dp
    val touchHeight = 44.dp
    val thumbWidth = 40.dp
    val thumbHeight = 24.dp

    val colors = LocalMaiDroidColors.current
    val hasRenderEffect = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val resolvedAccentColor = accentColor.copy(alpha = if (enabled) 1f else 0.45f)
    val resolvedTrackColor = if (enabled) trackColor else trackColor.copy(alpha = 0.5f)
    val thumbColor = if (enabled) Color.White else Color.White.copy(alpha = 0.65f)
    val fallbackStrokeColor = if (colors.isDark) Color.White.copy(alpha = 0.55f) else colors.glassStroke
    val shadowAlpha = if (colors.isDark) 0.16f else 0.10f

    val density = LocalDensity.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val thumbWidthPx = with(density) { thumbWidth.toPx() }
    val touchThumbSlopPx = with(density) { 10.dp.toPx() }
    val haptics = rememberLiquidHaptics()
    val trackBackdrop = rememberLayerBackdrop()
    val animationScope = rememberCoroutineScope()

    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestRange by rememberUpdatedState(valueRange)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestHapticStep by rememberUpdatedState(hapticStep)

    val initialClamped = latestValue().coerceIn(valueRange.start, valueRange.endInclusive)
    var isDragging by remember { mutableStateOf(false) }
    var didDrag by remember { mutableStateOf(false) }
    var downPositionX by remember { mutableFloatStateOf(0f) }
    var rawDragValue by remember { mutableFloatStateOf(initialClamped) }
    var currentHapticTick by remember {
        mutableIntStateOf(resolveLiquidSliderHapticTick(-1, initialClamped, valueRange, hapticStep))
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidthPx = constraints.maxWidth.toFloat()
        val travelPx = (trackWidthPx - thumbWidthPx).coerceAtLeast(0f)
        val latestTrackWidthPx by rememberUpdatedState(trackWidthPx)
        val latestTravelPx by rememberUpdatedState(travelPx)

        val rangeSpan = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
        val visibilityThreshold = (rangeSpan * 0.002f).coerceAtLeast(0.0005f)

        val emitValueWithHaptics: (Float, Boolean) -> Unit = { target, forceTapHaptic ->
            val nextTick = resolveLiquidSliderHapticTick(
                currentTick = currentHapticTick,
                value = target,
                valueRange = latestRange,
                tickStep = latestHapticStep
            )
            if (nextTick != currentHapticTick) {
                currentHapticTick = nextTick
                haptics.performTabStep()
            } else if (forceTapHaptic && latestHapticStep > 0f) {
                haptics.performTabStep()
            }
            latestOnValueChange(target)
        }

        val dampedDragAnimation = remember(animationScope, valueRange.start, valueRange.endInclusive, touchSlop, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = rawDragValue,
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.45f,
                consumeToggleGestures = true,
                horizontalLockSlopPx = touchSlop * 0.25f,
                onDragStarted = { position ->
                    if (!latestEnabled) return@DampedDragAnimation
                    isDragging = true
                    didDrag = false
                    downPositionX = position.x
                    currentHapticTick = resolveLiquidSliderHapticTick(
                        currentTick = -1,
                        value = rawDragValue,
                        valueRange = latestRange,
                        tickStep = latestHapticStep
                    )
                },
                onDragStopped = {
                    if (!latestEnabled) {
                        isDragging = false
                        didDrag = false
                        rawDragValue = latestValue().coerceIn(latestRange.start, latestRange.endInclusive)
                        return@DampedDragAnimation
                    }
                    if (!didDrag) {
                        // 轻点轨道时平滑弹出水滴滑行至点击位置，并给出一次明确反馈震动
                        val tapped = computeLiquidSliderValueForPosition(
                            positionX = downPositionX,
                            trackWidthPx = latestTrackWidthPx,
                            thumbWidthPx = thumbWidthPx,
                            valueRange = latestRange,
                            isLtr = isLtr
                        )
                        rawDragValue = tapped
                        isDragging = false
                        emitValueWithHaptics(tapped, true)
                        animateToValue(tapped)
                    } else {
                        isDragging = false
                        didDrag = false
                        latestOnValueChange(rawDragValue)
                    }
                },
                onDragCancelled = {
                    isDragging = false
                    didDrag = false
                    val external = latestValue().coerceIn(latestRange.start, latestRange.endInclusive)
                    rawDragValue = external
                    currentHapticTick = resolveLiquidSliderHapticTick(
                        currentTick = -1,
                        value = external,
                        valueRange = latestRange,
                        tickStep = latestHapticStep
                    )
                },
                onDrag = { _, dragAmount ->
                    if (!latestEnabled || dragAmount == Offset.Zero) return@DampedDragAnimation
                    val widthPx = latestTrackWidthPx
                    val travel = latestTravelPx.coerceAtLeast(1f)
                    if (!didDrag) {
                        didDrag = true
                        val thumbOffset = computeLiquidSliderThumbOffsetPx(
                            progress = progress,
                            trackWidthPx = widthPx,
                            thumbWidthPx = thumbWidthPx,
                            isLtr = isLtr
                        )
                        val anchorLeft = if (isLtr) thumbOffset else widthPx - thumbWidthPx + thumbOffset
                        val touchedThumb = downPositionX in
                            (anchorLeft - touchThumbSlopPx)..(anchorLeft + thumbWidthPx + touchThumbSlopPx)
                        if (!touchedThumb) {
                            // 从滑块之外直接起手横向拖拽时，先对齐手指位置再继续相对跟手
                            val jumpTarget = computeLiquidSliderValueForPosition(
                                positionX = downPositionX + dragAmount.x,
                                trackWidthPx = widthPx,
                                thumbWidthPx = thumbWidthPx,
                                valueRange = latestRange,
                                isLtr = isLtr
                            )
                            rawDragValue = jumpTarget
                            emitValueWithHaptics(jumpTarget, false)
                            return@DampedDragAnimation
                        }
                    }
                    val span = (latestRange.endInclusive - latestRange.start).coerceAtLeast(0.0001f)
                    val delta = span * (dragAmount.x / travel)
                    val next = if (isLtr) rawDragValue + delta else rawDragValue - delta
                    val clamped = next.coerceIn(latestRange.start, latestRange.endInclusive)
                    rawDragValue = clamped
                    emitValueWithHaptics(clamped, false)
                }
            )
        }

        // 拖动期间只跟随内部连续值，完全不回吃外部量化回声，保证滑块跟手零顿挫。
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { rawDragValue }
                .collectLatest { target ->
                    if (isDragging) {
                        dampedDragAnimation.updateValue(target)
                    }
                }
        }

        // 非拖动状态（点击预设档位或“恢复默认”）下外部值改变时，触发阻尼膨胀滑行落位。
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { latestValue().coerceIn(latestRange.start, latestRange.endInclusive) }
                .collectLatest { external ->
                    if (!isDragging && abs(rawDragValue - external) > 0.0005f) {
                        rawDragValue = external
                        currentHapticTick = resolveLiquidSliderHapticTick(
                            currentTick = -1,
                            value = external,
                            valueRange = latestRange,
                            tickStep = latestHapticStep
                        )
                        dampedDragAnimation.animateToValue(external)
                    }
                }
        }

        val interactiveModifier = if (enabled) dampedDragAnimation.modifier else Modifier
        val currentSemanticValue = latestValue().coerceIn(valueRange.start, valueRange.endInclusive)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(touchHeight)
                .semantics(mergeDescendants = true) {
                    progressBarRangeInfo = ProgressBarRangeInfo(currentSemanticValue, valueRange)
                    if (enabled) {
                        setProgress { target ->
                            val clamped = target.coerceIn(latestRange.start, latestRange.endInclusive)
                            rawDragValue = clamped
                            dampedDragAnimation.animateToValue(clamped)
                            latestOnValueChange(clamped)
                            true
                        }
                    }
                }
                .then(interactiveModifier),
            contentAlignment = if (isLtr) Alignment.CenterStart else Alignment.CenterEnd
        ) {
            // 轨道与橙色进度层写入 trackBackdrop，供上方按压透明后的水滴滑块实时折射出双色交界。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .layerBackdrop(trackBackdrop),
                contentAlignment = if (isLtr) Alignment.CenterStart else Alignment.CenterEnd
            ) {
                Box(
                    modifier = Modifier
                        .clip(Capsule())
                        .background(resolvedTrackColor)
                        .height(trackHeight)
                        .fillMaxWidth()
                )

                Box(
                    modifier = Modifier
                        .clip(Capsule())
                        .background(resolvedAccentColor)
                        .height(trackHeight)
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(constraints)
                            val fillWidth = (constraints.maxWidth * dampedDragAnimation.progress)
                                .fastRoundToInt()
                                .coerceIn(0, constraints.maxWidth)
                            layout(fillWidth, placeable.height) {
                                placeable.place(0, 0)
                            }
                        }
                )
            }

            // 悬浮液态水滴滑块：静置时为饱满实白胶囊，按住拖拽时膨胀并化为实时折射玻璃。
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationX = computeLiquidSliderThumbOffsetPx(
                            progress = dampedDragAnimation.progress,
                            trackWidthPx = trackWidthPx,
                            thumbWidthPx = thumbWidthPx,
                            isLtr = isLtr
                        )
                    }
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(
                            backdrop,
                            rememberBackdrop(trackBackdrop) { drawBackdrop ->
                                val progress = dampedDragAnimation.pressProgress
                                val scaleX = lerp(2f / 3f, 1f, progress)
                                val scaleY = lerp(0f, 1f, progress)
                                scale(scaleX, scaleY) {
                                    drawBackdrop()
                                }
                            }
                        ),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            blur(8f.dp.toPx() * (1f - progress))
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Ambient.copy(
                                width = Highlight.Ambient.width / 1.5f,
                                blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                                alpha = progress
                            )
                        },
                        shadow = {
                            Shadow(
                                radius = 4f.dp,
                                color = Color.Black.copy(alpha = shadowAlpha)
                            )
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            InnerShadow(
                                radius = 4f.dp * progress,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 12f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            // Android 8–11 无实时 RenderEffect 折射时保留轻透磨砂与主题适配边线，防止拖动水滴发虚隐身。
                            val minSurfaceAlpha = if (hasRenderEffect) 0f else 0.36f
                            drawRect(thumbColor.copy(alpha = lerp(1f, minSurfaceAlpha, progress)))
                            if (!hasRenderEffect && progress > 0.01f) {
                                val strokePx = 0.9.dp.toPx()
                                val inset = strokePx / 2f
                                drawRoundRect(
                                    color = fallbackStrokeColor.copy(alpha = fallbackStrokeColor.alpha * progress),
                                    topLeft = Offset(inset, inset),
                                    size = Size(size.width - strokePx, size.height - strokePx),
                                    cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                                    style = Stroke(width = strokePx)
                                )
                            }
                        }
                    )
                    .size(width = thumbWidth, height = thumbHeight)
            )
        }
    }
}
