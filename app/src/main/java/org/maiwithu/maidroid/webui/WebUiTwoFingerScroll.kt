package org.maiwithu.maidroid.webui

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.OnTouchListener
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 两指上下滑动直接滚动网页的外层滚动区，像触摸板一样，不被手指下面的子滚动块拦走。
 * 主页 WebUI 页签和全屏 WebUiActivity 共用（在 WebUiSessionHost 里装到 GeckoView 上）。
 *
 * 起因：单指滑动由网页内核按手指下面最里层的滚动块来滚，日志框、代码编辑器这类子块一旦占满屏幕，
 * 手指怎么放都落在它上面，外层页面就再也滚不动了。
 *
 * 一串触摸的流程：单指按下照常交给网页 -> 第二根手指按下，给网页补一个 CANCEL，作废它手上的单指滚动、长按和点击，
 * 这串触摸剩下的事件都不再交给网页 -> 网页扩展从两指中点所在元素往外找可滚动的祖先 ->
 * 两指中点的竖直位移超过触摸阈值后开始滚：每一下位移都先交给最外层，外层滚到头了剩下的才往里一层传 ->
 * 抬起一根手指就结束，按两指的速度用系统的 OverScroller 接着惯性滚，滚到头或手指再按下就停。
 */
internal class WebUiTwoFingerScroll(
    private val targetView: View,
    private val onBegin: (x: Float, y: Float, viewWidthPx: Int) -> Unit,
    private val onMove: (dy: Float, generation: Int?) -> Unit
) : OnTouchListener {
    private val viewConfiguration = ViewConfiguration.get(targetView.context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
    private val maxFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity
    private val flingScroller = OverScroller(targetView.context)
    private var velocityTracker: VelocityTracker? = null

    // 这串触摸已经转成两指滚动：从第二根手指按下起，到下一次单指按下为止，事件都不交给网页。
    private var active = false

    // 两指滚动已结束（只剩一根手指，或已经甩出惯性），剩下的事件吞掉，等全部抬起。
    private var ended = false

    // 中点的竖直位移已超过触摸阈值、正在滚；没超过之前的位移只累计不滚，两指轻点或搭在屏幕上不会让页面抖动。
    private var dragging = false
    private var slopTravel = 0f
    private var lastFocusY = 0f

    // 中点最后一次真正上下移动的时间：避免拖动后停顿再抬手仍然甩出惯性。
    private var lastMoveTime = 0L

    // 惯性滚动：OverScroller 上一帧的位置；每次停下都换一代，网页晚到的“滚到头了”回执只能停掉同一代的惯性。
    private var flingLastY = 0
    private var flingGeneration = 0

    private val attachListener = object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) = stopFling()
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (!targetView.isAttachedToWindow || !flingScroller.computeScrollOffset()) return
            val y = flingScroller.currY
            val dy = y - flingLastY
            flingLastY = y
            if (dy != 0) {
                onMove(dy.toFloat(), flingGeneration)
            }
            if (!flingScroller.isFinished) targetView.postOnAnimation(this)
        }
    }

    init {
        // 切到别的页签或关闭页面时视图离开窗口，惯性随之停下，不再往已经不显示的网页里发消息。
        targetView.addOnAttachStateChangeListener(attachListener)
    }

    /** 收到网页扩展传回来的惯性边界结果：只停掉当前还在跑的那一代惯性，旧代延迟回执直接忽略。 */
    fun onFlingMoveResult(canContinue: Boolean, generation: Int) {
        if (!canContinue && generation == flingGeneration) {
            stopFling()
        }
    }

    /** 宿主释放视图时停掉惯性、撤掉窗口监听并回收速度追踪器。 */
    fun release() {
        stopFling()
        targetView.removeOnAttachStateChangeListener(attachListener)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // 新的一串触摸：先停掉上一次的惯性（和原生滚动一样，手指一按就停），单指照常交给网页。
            stopFling()
            active = false
            ended = false
            return false
        }
        if (!active) {
            if (event.actionMasked != MotionEvent.ACTION_POINTER_DOWN) return false
            active = true
            cancelPageTouch(event)
            beginScroll(event)
            return true
        }

        velocityTracker?.addMovement(event)
        when (event.actionMasked) {
            // 结束后又按下第二根手指：重新开始一轮两指滚动；滚动中再加手指只换算中点，不让页面跳。
            MotionEvent.ACTION_POINTER_DOWN -> if (ended) beginScroll(event) else lastFocusY = focusY(event)
            MotionEvent.ACTION_MOVE -> if (!ended) onMoveTouch(event)
            MotionEvent.ACTION_POINTER_UP -> if (!ended) {
                if (event.pointerCount > 2) lastFocusY = focusY(event) else finishScroll(event, fling = true)
            }
            MotionEvent.ACTION_UP -> if (!ended) finishScroll(event, fling = true)
            MotionEvent.ACTION_CANCEL -> if (!ended) finishScroll(event, fling = false)
        }
        return true
    }

    /**
     * 第二根手指按下时给网页补一个 CANCEL：作废已开始的单指滚动、长按菜单和点击；
     * CANCEL 里只放网页已经见过的手指，剔除刚落下的这根，防止内核之后卡死在假触摸状态。
     */
    private fun cancelPageTouch(event: MotionEvent) {
        val knownIndexes = (0 until event.pointerCount).filter { it != event.actionIndex }
        val properties = Array(knownIndexes.size) { i ->
            MotionEvent.PointerProperties().also { event.getPointerProperties(knownIndexes[i], it) }
        }
        val coords = Array(knownIndexes.size) { i ->
            MotionEvent.PointerCoords().also { event.getPointerCoords(knownIndexes[i], it) }
        }
        val cancel = MotionEvent.obtain(
            event.downTime, event.eventTime, MotionEvent.ACTION_CANCEL,
            knownIndexes.size, properties, coords,
            event.metaState, event.buttonState, event.xPrecision, event.yPrecision,
            event.deviceId, event.edgeFlags, event.source, event.flags
        )
        // 直接调 onTouchEvent，不经 dispatchTouchEvent，免得又绕回本监听器。
        targetView.onTouchEvent(cancel)
        cancel.recycle()
    }

    /** 开始一轮两指滚动：停掉还在走的惯性，记下中点并通知网页扩展准备外层容器链。 */
    private fun beginScroll(event: MotionEvent) {
        stopFling()
        ended = false
        dragging = false
        slopTravel = 0f
        lastFocusY = focusY(event)
        lastMoveTime = event.eventTime
        val tracker = velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }
        tracker.clear()
        tracker.addMovement(event)
        onBegin(focusX(event), lastFocusY, targetView.width)
    }

    /** 两指移动：超过触摸阈值后才把位移交给网页扩展，拖动时不要求回执。 */
    private fun onMoveTouch(event: MotionEvent) {
        val focus = focusY(event)
        val dy = focus - lastFocusY
        lastFocusY = focus
        if (dy != 0f) lastMoveTime = event.eventTime
        if (!dragging) {
            slopTravel += dy
            dragging = abs(slopTravel) >= touchSlop
            return
        }
        if (dy != 0f) onMove(dy, null)
    }

    /** 结束两指滚动：抬手时若仍在连续滑动且速度足够，则启动 OverScroller 惯性。 */
    private fun finishScroll(event: MotionEvent, fling: Boolean) {
        ended = true
        val tracker = velocityTracker ?: return
        if (!fling || !dragging || event.eventTime - lastMoveTime > STOPPED_BEFORE_LIFT_MS) return
        tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
        var velocitySum = 0f
        for (index in 0 until event.pointerCount) {
            velocitySum += tracker.getYVelocity(event.getPointerId(index))
        }
        val velocityY = velocitySum / event.pointerCount
        if (abs(velocityY) < minFlingVelocity) return

        // 起点 0、上下不设边界：OverScroller 只负责算每帧增量，滚动到头由网页扩展回执通知停止。
        flingScroller.fling(0, 0, 0, velocityY.roundToInt(), 0, 0, -FLING_RANGE, FLING_RANGE)
        flingLastY = 0
        targetView.postOnAnimation(flingStep)
    }

    private fun stopFling() {
        flingGeneration += 1
        flingScroller.forceFinished(true)
        targetView.removeCallbacks(flingStep)
    }

    companion object {
        private const val FLING_RANGE = Int.MAX_VALUE / 2

        /** 抬起前中点多久没动就算已经停住、不甩惯性。 */
        private const val STOPPED_BEFORE_LIFT_MS = 50L

        /** 所有按着的手指的中点；抬起中的那根手指（ACTION_POINTER_UP 的 actionIndex）不算。 */
        private fun focusX(event: MotionEvent): Float = focus(event, MotionEvent::getX)

        private fun focusY(event: MotionEvent): Float = focus(event, MotionEvent::getY)

        private inline fun focus(event: MotionEvent, coordinate: MotionEvent.(Int) -> Float): Float {
            val skipIndex = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
            var sum = 0f
            var count = 0
            for (index in 0 until event.pointerCount) {
                if (index == skipIndex) continue
                sum += event.coordinate(index)
                count += 1
            }
            return if (count == 0) 0f else sum / count
        }
    }
}
