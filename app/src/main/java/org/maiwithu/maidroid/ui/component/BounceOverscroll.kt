package org.maiwithu.maidroid.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// 非线性橡皮筋阻尼：初段轻盈跟手（0.56x），越靠近最大拉伸距离阻力越大，渐近不越界。
private const val RubberBandBaseFactor = 0.56f
private const val OverscrollSettleThresholdPx = 0.5f
private const val FlingLeftoverTriggerPxPerSec = 120f
private const val SplitHeaderFollowRatio = 0.18f

/**
 * 计算单步拖拽后的弹性过冲位移（单位：px）。
 * 仅用于向界外拖拽时的渐进阻尼叠加；反向回推由外层按 1:1 先抵消当前过冲，保证手感跟手不拖泥带水。
 */
internal fun computeRubberBandStep(
    currentOffsetPx: Float,
    deltaPx: Float,
    maxOffsetPx: Float
): Float {
    if (maxOffsetPx <= 0f || deltaPx == 0f) return currentOffsetPx
    val clampedCurrent = currentOffsetPx.coerceIn(-maxOffsetPx, maxOffsetPx)
    val ratio = (abs(clampedCurrent) / maxOffsetPx).coerceIn(0f, 1f)
    // 二次衰减增益：刚到边界时阻尼轻快，接近极限时趋近于零，避免生硬撞墙。
    val damping = RubberBandBaseFactor * (1f - ratio) * (1f - ratio)
    return (clampedCurrent + deltaPx * damping).coerceIn(-maxOverscrollSafe(maxOffsetPx), maxOverscrollSafe(maxOffsetPx))
}

private fun maxOverscrollSafe(maxOffsetPx: Float): Float = maxOffsetPx.coerceAtLeast(0f)

/**
 * 通用弹性阻尼越界滚动效果：用真实的橡胶阻力平移 + 临界阻尼弹簧回弹替代 Android 12+ 的画面拉伸变形。
 *
 * 当 [splitTopHeader] 为 true 时，顶部下拉过冲（`overscrollY > 0`）不再整体移动列表裁剪框，
 * 而是由页面内部的标题（[overscrollHeader]）与卡片内容区（[overscrollBody]）按不同比例各自平移，
 * 产生大标题微幅跟随下沉、下方卡片大幅拉开留白的视差过冲效果；底部上拉过冲仍保持整体上移。
 */
internal class BounceOverscrollEffect(
    private val maxOverscrollPx: Float,
    private val splitTopHeader: Boolean = false
) : OverscrollEffect {

    var overscrollX by mutableFloatStateOf(0f)
        private set
    var overscrollY by mutableFloatStateOf(0f)
        private set

    /** 当前顶部下拉的弹性距离（px，始终 >= 0），供视差标题与卡片主体读取。 */
    val topOverscrollPx: Float
        get() = overscrollY.coerceAtLeast(0f)

    private var flingJobX: Job? = null
    private var flingJobY: Job? = null
    private val bounceNode = BounceModifierNode()

    override val node: DelegatableNode
        get() = bounceNode

    override val isInProgress: Boolean
        get() = abs(overscrollX) > OverscrollSettleThresholdPx ||
            abs(overscrollY) > OverscrollSettleThresholdPx

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset
    ): Offset {
        // 手指重新接触并拖动时立刻接管控制权，打断回弹动画
        if (source == NestedScrollSource.UserInput) {
            if (abs(delta.x) > 0f) flingJobX?.cancel()
            if (abs(delta.y) > 0f) flingJobY?.cancel()
        }

        // 先按 1:1 消耗反向拉回的位移：只有过冲归零后，剩余手指位移才交还给内部列表滚回去
        val preConsumedX = consumeOppositeOverscroll(delta.x, isVertical = false)
        val preConsumedY = consumeOppositeOverscroll(delta.y, isVertical = true)
        val preConsumed = Offset(preConsumedX, preConsumedY)

        val remainingForScroll = delta - preConsumed
        val consumedByScroll = performScroll(remainingForScroll)
        val leftover = remainingForScroll - consumedByScroll

        // 仅在用户主动拖拽撞到边界后累积弹性偏移，避免程序触发的嵌套回传引起抖动
        var postConsumedX = 0f
        var postConsumedY = 0f
        if (source == NestedScrollSource.UserInput) {
            if (abs(leftover.x) > OverscrollSettleThresholdPx) {
                overscrollX = computeRubberBandStep(overscrollX, leftover.x, maxOverscrollPx)
                postConsumedX = leftover.x
            }
            if (abs(leftover.y) > OverscrollSettleThresholdPx) {
                overscrollY = computeRubberBandStep(overscrollY, leftover.y, maxOverscrollPx)
                postConsumedY = leftover.y
            }
        }

        return preConsumed + consumedByScroll + Offset(postConsumedX, postConsumedY)
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity
    ) {
        val inOverscrollX = abs(overscrollX) > OverscrollSettleThresholdPx
        val inOverscrollY = abs(overscrollY) > OverscrollSettleThresholdPx

        if (inOverscrollX || inOverscrollY) {
            // 已经处在界外时松手：直接由弹簧吸回原点，不再喂给列表容器引起穿帮滚动
            if (inOverscrollX) launchSpringReturn(isVertical = false, initialVelocity = velocity.x * 0.22f)
            if (inOverscrollY) launchSpringReturn(isVertical = true, initialVelocity = velocity.y * 0.22f)
            return
        }

        // 在列表中间快速划动松手：先正常惯性滚动，撞到顶部/底部后的剩余动能转成一次自然过冲缓冲
        val consumed = performFling(velocity)
        val leftover = velocity - consumed

        if (abs(leftover.x) > FlingLeftoverTriggerPxPerSec) {
            launchInertialBounce(isVertical = false, leftoverVelocity = leftover.x)
        }
        if (abs(leftover.y) > FlingLeftoverTriggerPxPerSec) {
            launchInertialBounce(isVertical = true, leftoverVelocity = leftover.y)
        }
    }

    private fun consumeOppositeOverscroll(delta: Float, isVertical: Boolean): Float {
        val current = if (isVertical) overscrollY else overscrollX
        if (abs(current) <= OverscrollSettleThresholdPx || delta == 0f) return 0f
        // 同号说明正在往更深处拉，跳过 pre-scroll
        if (sign(delta) == sign(current)) return 0f

        val next = current + delta
        val consumed = if (sign(next) != sign(current) || abs(next) <= OverscrollSettleThresholdPx) {
            // 这一步足够把过冲完全消掉：只消费掉归零所需的位移，余下的交给列表正常滚动
            setAxisOffset(isVertical, 0f)
            -current
        } else {
            setAxisOffset(isVertical, next)
            delta
        }
        return consumed
    }

    private fun launchSpringReturn(isVertical: Boolean, initialVelocity: Float) {
        val startOffset = if (isVertical) overscrollY else overscrollX
        // 限制反向穿零：如果本来在顶部下拉过冲（>0），回弹只在 [0, max] 内收口，不冲进底部过冲
        val minBound = if (startOffset > 0f) 0f else -maxOverscrollPx
        val maxBound = if (startOffset < 0f) 0f else maxOverscrollPx

        launchAxisAnimation(isVertical) {
            val anim = Animatable(startOffset)
            anim.updateBounds(lowerBound = minBound, upperBound = maxBound)
            try {
                anim.animateTo(
                    targetValue = 0f,
                    initialVelocity = initialVelocity,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = 280f
                    )
                ) {
                    setAxisOffset(isVertical, value)
                }
            } finally {
                if (abs(if (isVertical) overscrollY else overscrollX) <= OverscrollSettleThresholdPx) {
                    setAxisOffset(isVertical, 0f)
                }
            }
        }
    }

    private fun launchInertialBounce(isVertical: Boolean, leftoverVelocity: Float) {
        val maxInertialOverscroll = maxOverscrollPx * 0.55f
        val minBound = if (leftoverVelocity > 0f) 0f else -maxInertialOverscroll
        val maxBound = if (leftoverVelocity < 0f) 0f else maxInertialOverscroll

        launchAxisAnimation(isVertical) {
            val anim = Animatable(0f)
            anim.updateBounds(lowerBound = minBound, upperBound = maxBound)
            try {
                anim.animateTo(
                    targetValue = 0f,
                    initialVelocity = leftoverVelocity * 0.34f,
                    animationSpec = spring(
                        dampingRatio = 0.92f,
                        stiffness = 260f
                    )
                ) {
                    setAxisOffset(isVertical, value)
                }
            } finally {
                if (abs(if (isVertical) overscrollY else overscrollX) <= OverscrollSettleThresholdPx) {
                    setAxisOffset(isVertical, 0f)
                }
            }
        }
    }

    private fun launchAxisAnimation(isVertical: Boolean, block: suspend () -> Unit) {
        if (!bounceNode.isAttached) {
            setAxisOffset(isVertical, 0f)
            return
        }
        val job = bounceNode.coroutineScope.launch { block() }
        if (isVertical) {
            flingJobY?.cancel()
            flingJobY = job
        } else {
            flingJobX?.cancel()
            flingJobX = job
        }
    }

    private fun setAxisOffset(isVertical: Boolean, value: Float) {
        val cleanValue = if (abs(value) <= 0.1f && value != 0f) 0f else value
        if (isVertical) {
            overscrollY = cleanValue
        } else {
            overscrollX = cleanValue
        }
    }

    private inner class BounceModifierNode : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(
            measurable: Measurable,
            constraints: Constraints
        ): MeasureResult {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) {
                // 始终通过图形层属性更新偏移，确保拖拽和弹簧回弹期间只改动 RenderNode 平移而不重建图层或重排
                placeable.placeWithLayer(0, 0) {
                    translationX = overscrollX
                    // 视差过冲模式且正在顶部下拉时，保持外层容器原地不动，由内部标题与卡片分层按比例偏移
                    translationY = if (splitTopHeader && overscrollY > 0f) 0f else overscrollY
                }
            }
        }

        override fun onDetach() {
            flingJobX?.cancel()
            flingJobY?.cancel()
            overscrollX = 0f
            overscrollY = 0f
        }
    }
}

/** 全局通过 [LocalOverscrollFactory] 提供的工厂：给每个列表分配独立的弹性平移效果。 */
internal data class BounceOverscrollFactory(
    private val maxOverscrollPx: Float
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect =
        BounceOverscrollEffect(maxOverscrollPx = maxOverscrollPx, splitTopHeader = false)
}

/** 创建支持“大标题微幅跟随 + 内容卡片大幅弹性下移”视差过冲的页面级滚动效果。 */
@Composable
internal fun rememberSplitHeaderOverscrollEffect(
    maxPullDp: Dp = 280.dp
): BounceOverscrollEffect {
    val density = LocalDensity.current
    val maxPullPx = with(density) { maxPullDp.toPx() }
    return remember(maxPullPx) {
        BounceOverscrollEffect(maxOverscrollPx = maxPullPx, splitTopHeader = true)
    }
}

/** 大标题区（如“设置”“消息平台”）修饰符：顶部下拉时只轻柔下沉 18%，避免切边又保有整体联动感。 */
internal fun Modifier.overscrollHeader(effect: BounceOverscrollEffect?): Modifier =
    if (effect == null) {
        this
    } else {
        graphicsLayer {
            translationY = effect.topOverscrollPx * SplitHeaderFollowRatio
        }
    }

/** 列表卡片区修饰符：顶部下拉时完整跟手下沉，在大标题与第一张卡片之间顺滑拉开弹性呼吸空间。 */
internal fun Modifier.overscrollBody(effect: BounceOverscrollEffect?): Modifier =
    if (effect == null) {
        this
    } else {
        graphicsLayer {
            translationY = effect.topOverscrollPx
        }
    }
