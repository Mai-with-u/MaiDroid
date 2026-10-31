package org.maiwithu.maidroid.webui

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.webkit.WebView
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 两指上下滑动直接滚动网页的外层滚动区，像触摸板一样，不被手指下面的子滚动块拦走。
 * 主页 WebUI 页签和全屏 WebUiActivity 共用（在 createWebUiWebView 里装上）。
 *
 * 起因：单指滑动由网页内核按手指下面最里层的滚动块来滚，日志框、代码编辑器这类子块一旦占满屏幕，
 * 手指怎么放都落在它上面，外层页面就再也滚不动了。
 *
 * 一串触摸的流程：单指按下照常交给网页 -> 第二根手指按下，给网页补一个 CANCEL，作废它手上的单指滚动、长按和点击，
 * 这串触摸剩下的事件都不再交给网页 -> 网页脚本从两指中点所在元素往外找可滚动的祖先 ->
 * 两指中点的竖直位移超过触摸阈值后开始滚：每一下位移都先交给最外层，外层滚到头了剩下的才往里一层传 ->
 * 抬起一根手指就结束，按两指的速度用系统的 OverScroller 接着惯性滚，滚到头或手指再按下就停。
 * 不锁定某一层：真机上竖屏首页的外层 main 只能滚 98px，锁住它的话一次手势滚完这一小截就卡住，里面的内容区一动不动。
 * 滚动本身在网页里做（改目标容器的 scrollTop）：外层容器往往是页面里的一个 div（WebUI 主内容区是 Radix ScrollArea），
 * WebView 自己的 scrollBy 只能滚整个文档，够不着它。
 */
internal class WebUiTwoFingerScroll(private val webView: WebView) : View.OnTouchListener {
    private val viewConfiguration = ViewConfiguration.get(webView.context)
    private val touchSlop = viewConfiguration.scaledTouchSlop
    private val minFlingVelocity = viewConfiguration.scaledMinimumFlingVelocity
    private val maxFlingVelocity = viewConfiguration.scaledMaximumFlingVelocity
    private val flingScroller = OverScroller(webView.context)
    private var velocityTracker: VelocityTracker? = null

    // 这串触摸已经转成两指滚动：从第二根手指按下起，到下一次单指按下为止，事件都不交给网页。
    private var active = false

    // 两指滚动已结束（只剩一根手指，或已经甩出惯性），剩下的事件吞掉，等全部抬起。
    private var ended = false

    // 中点的竖直位移已超过触摸阈值、正在滚；没超过之前的位移只累计不滚，两指轻点或搭在屏幕上不会让页面抖动。
    private var dragging = false
    private var slopTravel = 0f
    private var lastFocusY = 0f

    // 中点最后一次真正上下移动的时间。手指停住时触摸屏往往不再报移动事件，VelocityTracker 只看得到停住前的样本，
    // 抬起时会算出停住前的速度；真机上两指拖完停 300ms 再抬，页面照样被甩出去一大截。抬起时离这次移动太久就不甩。
    private var lastMoveTime = 0L

    // 惯性滚动：OverScroller 上一帧的位置；每次停下都换一代，网页晚到的“滚到头了”回执只能停掉同一代的惯性。
    private var flingLastY = 0
    private var flingGeneration = 0

    private val flingStep = object : Runnable {
        override fun run() {
            if (!webView.isAttachedToWindow || !flingScroller.computeScrollOffset()) return
            val y = flingScroller.currY
            val dy = y - flingLastY
            flingLastY = y
            if (dy != 0) {
                val generation = flingGeneration
                webView.evaluateJavascript(moveScript(dy.toFloat())) { canContinue ->
                    if (canContinue == "false" && generation == flingGeneration) stopFling()
                }
            }
            if (!flingScroller.isFinished) webView.postOnAnimation(this)
        }
    }

    init {
        // 切到别的页签或关闭页面时 WebView 离开窗口，惯性随之停下，不再往已经不显示的网页里发脚本。
        webView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = stopFling()
        })
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
            MotionEvent.ACTION_MOVE -> if (!ended) onMove(event)
            MotionEvent.ACTION_POINTER_UP -> if (!ended) {
                if (event.pointerCount > 2) lastFocusY = focusY(event) else finishScroll(event, fling = true)
            }
            MotionEvent.ACTION_UP -> if (!ended) finishScroll(event, fling = true)
            MotionEvent.ACTION_CANCEL -> if (!ended) finishScroll(event, fling = false)
        }
        return true
    }

    /**
     * 第二根手指按下时给网页补一个 CANCEL，和父视图拦截触摸时系统发给子视图的一样：
     * 子滚动块已经开始的单指滚动、长按菜单、按下态和即将触发的点击全部作废。
     * 直接调 onTouchEvent，不经 dispatchTouchEvent，免得又绕回本监听器。
     */
    private fun cancelPageTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        webView.onTouchEvent(cancel)
        cancel.recycle()
    }

    /** 开始一轮两指滚动：停掉还在走的惯性，记下中点，速度从这里重新算，网页按中点找出这次可能要滚的容器。 */
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
        webView.evaluateJavascript(beginScript(focusX(event), lastFocusY, webView.width), null)
    }

    /**
     * 两指移动：中点竖直位移先攒够触摸阈值才开始滚，越过阈值的这一下不补前面攒的位移，页面不会突然跳一截；
     * 之后每次移动把位移原样交给网页（手指往上 = 内容往上 = 往下翻）。滚动中不看回执，滚到头就停在头上。
     */
    private fun onMove(event: MotionEvent) {
        val focus = focusY(event)
        val dy = focus - lastFocusY
        lastFocusY = focus
        if (dy != 0f) lastMoveTime = event.eventTime
        if (!dragging) {
            slopTravel += dy
            dragging = abs(slopTravel) >= touchSlop
            return
        }
        if (dy != 0f) webView.evaluateJavascript(moveScript(dy), null)
    }

    /**
     * 结束两指滚动：抬起手指时按两指的平均竖直速度甩出惯性；还没开始滚、抬起前已经停住、速度太小都直接停；
     * 系统收回触摸（CANCEL）时不甩。
     */
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

        // 起点 0、上下不设边界：OverScroller 只负责按系统手感算每一帧走多远，边界由网页回执判断。
        flingScroller.fling(0, 0, 0, velocityY.roundToInt(), 0, 0, -FLING_RANGE, FLING_RANGE)
        flingLastY = 0
        webView.postOnAnimation(flingStep)
    }

    private fun stopFling() {
        flingGeneration += 1
        flingScroller.forceFinished(true)
        webView.removeCallbacks(flingStep)
    }

    companion object {
        private const val FLING_RANGE = Int.MAX_VALUE / 2

        /** 抬起前中点多久没动就算已经停住、不甩惯性：手指移动时每帧（60Hz 约 16ms）都有新位置，停过三帧左右才算停。 */
        private const val STOPPED_BEFORE_LIFT_MS = 50L

        /**
         * 网页里的两指滚动脚本，同一个页面只装一次（页面刷新后由下一次 begin 重新装上）。
         * begin(x, y, viewWidth)：x、y 是两指中点在 WebView 里的物理像素，viewWidth 是 WebView 的物理宽度；
         * 用可视视口的 CSS 宽度除以它得到“每物理像素多少 CSS px”，横屏桌面版和竖屏手机版的缩放都由此换算。
         * 从中点下的元素往外收集可竖直滚动的祖先（含文档本身），按从外到内排好。
         * move(dy)：dy 是手指竖直位移的物理像素，往下为正（手指往下 = 往上翻）。换算成 CSS px 后从最外层往里分：
         * 每层滚到头为止，剩下的交给下一层，所以外层页面总是先动，子块只在外层到头时才接着滚；方向反过来时又是外层先动。
         * 每层自己记小数位置，免得每次被取整吃掉零头；网页自己改过滚动位置（比如聊天记录自动滚到底）就以网页的为准接着滚。
         * 返回这个方向上还有没有哪一层能接着滚，惯性据此停下。用 behavior: 'instant'，不受网页 scroll-behavior: smooth 影响。
         */
        private val installScript = """
            (function() {
              if (window.__maidroidTwoFingerScroll) return;
              var isScroller = function(el) {
                if (el.scrollHeight - el.clientHeight < 1) return false;
                if (el === document.scrollingElement) return true;
                var overflowY = getComputedStyle(el).overflowY;
                return overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay';
              };
              var state = { ratio: 1, chain: [], positions: new Map() };
              var positionOf = function(el) {
                var entry = state.positions.get(el);
                if (!entry || Math.abs(el.scrollTop - entry.applied) > 1) {
                  entry = { pos: el.scrollTop, applied: el.scrollTop };
                  state.positions.set(el, entry);
                }
                return entry;
              };
              window.__maidroidTwoFingerScroll = {
                begin: function(x, y, viewWidth) {
                  var viewport = window.visualViewport;
                  state.ratio = ((viewport && viewport.width) || window.innerWidth) / viewWidth;
                  var node = document.elementFromPoint(
                    x * state.ratio + (viewport ? viewport.offsetLeft : 0),
                    y * state.ratio + (viewport ? viewport.offsetTop : 0)
                  );
                  var chain = [];
                  while (node) {
                    if (node.nodeType === 1 && isScroller(node)) chain.unshift(node);
                    node = node.parentElement || (node.getRootNode && node.getRootNode().host) || null;
                  }
                  var root = document.scrollingElement;
                  if (root && chain.indexOf(root) < 0 && isScroller(root)) chain.unshift(root);
                  state.chain = chain;
                  state.positions = new Map();
                },
                move: function(dy) {
                  var delta = -dy * state.ratio;
                  if (!delta) return true;
                  var remaining = delta;
                  var canContinue = false;
                  for (var i = 0; i < state.chain.length; i++) {
                    var el = state.chain[i];
                    if (!el.isConnected) continue;
                    var max = el.scrollHeight - el.clientHeight;
                    var entry = positionOf(el);
                    if (remaining) {
                      var next = Math.max(0, Math.min(max, entry.pos + remaining));
                      if (next !== entry.pos) {
                        remaining -= next - entry.pos;
                        if (Math.abs(remaining) < 0.01) remaining = 0;
                        entry.pos = next;
                        el.scrollTo({ top: next, behavior: 'instant' });
                        entry.applied = el.scrollTop;
                      }
                    }
                    if (delta > 0 ? entry.pos < max - 0.5 : entry.pos > 0.5) canContinue = true;
                  }
                  return canContinue;
                }
              };
            })();
        """.trimIndent()

        /** 装好脚本（已装过则跳过）并开始一轮两指滚动。数字用字符串模板拼，小数点不随系统语言变成逗号。 */
        fun beginScript(x: Float, y: Float, viewWidthPx: Int): String =
            "$installScript\nwindow.__maidroidTwoFingerScroll.begin($x, $y, $viewWidthPx);"

        /** 按手指竖直位移滚一下，返回 "true"/"false"（还能不能接着滚）；页面刚刷新、脚本不在时返回 false。 */
        fun moveScript(dy: Float): String =
            "window.__maidroidTwoFingerScroll ? window.__maidroidTwoFingerScroll.move($dy) : false"

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
