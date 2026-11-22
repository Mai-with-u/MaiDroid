package org.maiwithu.maidroid.ui.component

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/** 液态按钮在按压与微拽手势下的形变参数（像素位移 + 双轴果冻拉伸倍率）。 */
internal data class LiquidButtonDragTransform(
    val translationXPx: Float,
    val translationYPx: Float,
    val scaleX: Float,
    val scaleY: Float
)

/** 根据组件尺寸与手指相对偏移计算双曲正切微位移和角度耦合果冻缩放。 */
internal fun computeLiquidButtonDragTransform(
    widthPx: Float,
    heightPx: Float,
    maxDragHeightPx: Float,
    pressProgress: Float,
    offsetX: Float,
    offsetY: Float
): LiquidButtonDragTransform {
    if (widthPx <= 0f || heightPx <= 0f) {
        return LiquidButtonDragTransform(0f, 0f, 1f, 1f)
    }
    val clampedProgress = pressProgress.coerceIn(0f, 1f)
    val baseScale = lerp(1f, 1f + maxDragHeightPx / heightPx, clampedProgress)
    val maxOffset = minOf(widthPx, heightPx).coerceAtLeast(1f)
    val maxDimension = maxOf(widthPx, heightPx).coerceAtLeast(1f)
    val initialDerivative = 0.05f
    val translationX = maxOffset * tanh(initialDerivative * offsetX / maxOffset)
    val translationY = maxOffset * tanh(initialDerivative * offsetY / maxOffset)

    val maxDragScale = maxDragHeightPx / heightPx
    val angle = atan2(offsetY, offsetX)
    val scaleX = baseScale +
        maxDragScale * abs(cos(angle) * offsetX / maxDimension) * (widthPx / heightPx).coerceAtMost(1f)
    val scaleY = baseScale +
        maxDragScale * abs(sin(angle) * offsetY / maxDimension) * (heightPx / widthPx).coerceAtMost(1f)
    return LiquidButtonDragTransform(
        translationXPx = translationX,
        translationYPx = translationY,
        scaleX = scaleX,
        scaleY = scaleY
    )
}

/** 拖动水滴滑块时整条胶囊面板的微幅阻尼随动位移（最多 ±maxPanelOffsetPx）。 */
internal fun computeLiquidPanelOffsetPx(
    accumulatedOffsetPx: Float,
    containerSpanPx: Float,
    maxPanelOffsetPx: Float
): Float {
    if (containerSpanPx <= 0f || maxPanelOffsetPx <= 0f) return 0f
    val fraction = (accumulatedOffsetPx / containerSpanPx).fastCoerceIn(-1f, 1f)
    return maxPanelOffsetPx * fraction.sign * EaseOut.transform(abs(fraction))
}

/** 结算液态开关手势结束后的目标开关状态：被外部滚动取消时回弹保持原状态。 */
internal fun resolveToggleDragOutcome(
    wasDragged: Boolean,
    cancelled: Boolean,
    targetFraction: Float,
    currentlyChecked: Boolean
): Boolean = when {
    cancelled -> currentlyChecked
    wasDragged -> targetFraction >= 0.5f
    else -> !currentlyChecked
}

/**
 * 液态玻璃通用的非消费式拖拽监听器：
 * 按下即同步驱动按压形变与高亮追踪，一旦手势被外层点击或滚动容器消费（如列表上下滑动）立即转入 onDragCancel，
 * 既能保留细腻的手指跟手响应，又不抢占父级点击/滚动手势。
 */
internal suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)
        val upEvent = dragPointer(
            pointerId = initialDown.id,
            onDrag = { onDrag(it, it.positionChange()) }
        )
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.dragPointer(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null

    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed } ?: return dragEvent
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}

/**
 * 阻尼弹簧拖拽控制器（对标 AndroidLiquidGlass DampedDragAnimation）：
 * 同步维护一维滑块索引值、拖拽速度拉伸因子与水滴按压膨胀缩放（scaleX/scaleY），
 * 用于底部四栏导航、横屏竖向侧栏、开关滑块及分段接入选择器。
 */
internal class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDragCancelled: DampedDragAnimation.() -> Unit = onDragStopped,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit
) {
    private val valueAnimationSpec = spring(1f, 1000f, visibilityThreshold)
    private val velocityAnimationSpec = spring(0.5f, 300f, visibilityThreshold * 10f)
    private val pressProgressAnimationSpec = spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec = spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec = spring(0.7f, 250f, 0.001f)

    private val valueAnimation = Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation = Animatable(0f, 5f)
    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val scaleXAnimation = Animatable(initialScale, 0.001f)
    private val scaleYAnimation = Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()
    private val velocityTracker = VelocityTracker()
    private var releaseJob: Job? = null

    val value: Float get() = valueAnimation.value
    val progress: Float
        get() {
            val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
            return ((value - valueRange.start) / span).fastCoerceIn(0f, 1f)
        }
    val targetValue: Float get() = valueAnimation.targetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(this) {
        inspectDragGestures(
            onDragStart = { down ->
                onDragStarted(down.position)
                press()
            },
            onDragEnd = {
                onDragStopped()
                release()
            },
            onDragCancel = {
                onDragCancelled()
                release()
            }
        ) { _, dragAmount ->
            onDrag(size, dragAmount)
        }
    }

    /** 按下瞬间取消残留回弹协程并立刻把水滴弹开膨胀。 */
    fun press() {
        releaseJob?.cancel()
        releaseJob = null
        velocityTracker.resetTracking()
        animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    /** 等位移动画临近落位后再柔和收拢水滴形态，保证滑动到目标前玻璃仍保持饱满膨胀态。 */
    fun release() {
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            awaitFrame()
            if (value != targetValue) {
                val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
                val threshold = span * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - valueAnimation.targetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
        }
    }

    /** 拖拽跟手更新目标值并实时采样速度，驱动形变拉伸。 */
    fun updateValue(value: Float) {
        val clamped = value.coerceIn(valueRange)
        animationScope.launch {
            launch { valueAnimation.animateTo(clamped, valueAnimationSpec) { updateVelocity() } }
        }
    }

    /** 程序驱动跳转（如点击页签或外部状态变更）：先短暂膨胀滑行再自动收拢。 */
    fun animateToValue(value: Float) {
        animationScope.launch {
            mutatorMutex.mutate {
                press()
                val clamped = value.coerceIn(valueRange)
                launch { valueAnimation.animateTo(clamped, valueAnimationSpec) }
                if (velocity != 0f) {
                    launch { velocityAnimation.animateTo(0f, velocityAnimationSpec) }
                }
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(System.currentTimeMillis(), Offset(value, 0f))
        val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.001f)
        val targetVelocity = velocityTracker.calculateVelocity().x / span
        animationScope.launch { velocityAnimation.animateTo(targetVelocity, velocityAnimationSpec) }
    }
}

/**
 * 跟随指尖的交互式高光渲染器：
 * - Android 13+（API 33+）直接运行 AGSL 径向脉冲高光着色器；
 * - Android 8–12（API 26–32）使用同等位置的径向渐变画刷叠加 BlendMode.Plus，保证全机型都具备随指高光响应。
 */
internal class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset }
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero
    val pressProgress: Float get() = pressProgressAnimation.value
    val offset: Offset get() = positionAnimation.value - startPosition

    private val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        RuntimeShader(InteractiveHighlightShaderSource)
    } else {
        null
    }

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            val resolved = position(size, positionAnimation.value)
            val clampedCenter = Offset(
                resolved.x.fastCoerceIn(0f, size.width),
                resolved.y.fastCoerceIn(0f, size.height)
            )
            val radius = (size.minDimension * 1.5f).coerceAtLeast(1f)
            drawRect(Color.White.copy(alpha = 0.08f * progress), blendMode = BlendMode.Plus)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
                shader.apply {
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(alpha = 0.15f * progress).toArgb())
                    setFloatUniform("radius", radius)
                    setFloatUniform("position", clampedCenter.x, clampedCenter.y)
                }
                drawRect(ShaderBrush(shader), blendMode = BlendMode.Plus)
            } else {
                drawRect(
                    brush = Brush.radialGradient(
                        0.0f to Color.White.copy(alpha = 0.18f * progress),
                        0.5f to Color.White.copy(alpha = 0.10f * progress),
                        1.0f to Color.Transparent,
                        center = clampedCenter,
                        radius = radius
                    ),
                    blendMode = BlendMode.Plus
                )
            }
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                startPosition = down.position
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                    launch { positionAnimation.snapTo(startPosition) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            },
            onDragCancel = {
                animationScope.launch {
                    launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                    launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
                }
            }
        ) { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
    }
}

private const val InteractiveHighlightShaderSource = """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}
"""
