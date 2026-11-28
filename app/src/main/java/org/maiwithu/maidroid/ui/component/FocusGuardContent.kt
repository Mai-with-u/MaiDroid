package org.maiwithu.maidroid.ui.component

import android.content.Context
import android.graphics.Rect
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.R as AndroidR

/**
 * 根视图向下派发兜底焦点请求时的处理策略：
 * - 触摸模式下直接吞掉，避免移除聚焦的 AndroidView 时把焦点强塞给 AndroidComposeView 触发重入崩溃或底层 WebView 焦点框；
 * - 非触摸模式（外接键盘/方向键导航）下推迟到当前消息循环末尾，等本帧 Compose applyChanges 结束后再分发焦点。
 */
internal enum class RootFocusDispatchDecision {
    IgnoreInTouchMode,
    DeferNonTouchFocus
}

internal fun decideRootFocusDispatch(isInTouchMode: Boolean): RootFocusDispatchDecision =
    if (isInTouchMode) RootFocusDispatchDecision.IgnoreInTouchMode else RootFocusDispatchDecision.DeferNonTouchFocus

internal fun shouldRunDeferredRootFocus(
    isAttachedToWindow: Boolean,
    isInTouchMode: Boolean,
    hasFocus: Boolean
): Boolean = isAttachedToWindow && !isInTouchMode && !hasFocus

/**
 * 包裹 Activity 根 [ComposeView] 的焦点防重入容器：
 * 当 Compose 在 applyChanges -> Operation.RemoveNode 中卸载仍持有焦点的 AndroidView（如 TerminalView、GeckoView）时，
 * Android ViewGroup.removeViewInternal 会同步回调 rootViewRequestFocus()，从 DecorView 一路向下进入
 * AndroidComposeView.requestFocus() -> FocusOwner.focusSearch() -> LazyList 越界重测与 subcompose，
 * 此时外层 pending composition 尚未应用完毕，会直接抛出 ComposeRuntimeError 闪退。
 *
 * 这里拦截自上而下的兜底焦点查找；用户点击输入框、终端或网页触发的是子视图自下而上的 requestChildFocus，不受影响。
 */
internal class FocusGuardFrameLayout(context: Context) : FrameLayout(context) {
    private var deferredFocusRunnable: Runnable? = null

    override fun onRequestFocusInDescendants(
        direction: Int,
        previouslyFocusedRect: Rect?
    ): Boolean {
        return when (decideRootFocusDispatch(isInTouchMode)) {
            RootFocusDispatchDecision.IgnoreInTouchMode -> false
            RootFocusDispatchDecision.DeferNonTouchFocus -> {
                scheduleDeferredFocusSearch(direction, previouslyFocusedRect)
                false
            }
        }
    }

    // 合并同一帧内的多次兜底焦点请求，延后到当前同步移除/applyChanges 栈彻底退出后再给外接键盘补焦点。
    private fun scheduleDeferredFocusSearch(direction: Int, previouslyFocusedRect: Rect?) {
        deferredFocusRunnable?.let(::removeCallbacks)
        val rectCopy = previouslyFocusedRect?.let(::Rect)
        val runnable = Runnable {
            deferredFocusRunnable = null
            if (!shouldRunDeferredRootFocus(isAttachedToWindow, isInTouchMode, hasFocus())) {
                return@Runnable
            }
            super.onRequestFocusInDescendants(direction, rectCopy)
        }
        deferredFocusRunnable = runnable
        post(runnable)
    }

    override fun onDetachedFromWindow() {
        deferredFocusRunnable?.let(::removeCallbacks)
        deferredFocusRunnable = null
        super.onDetachedFromWindow()
    }
}

/**
 * 与 ComponentActivity.setContent 等价的挂载入口，但将根 [ComposeView] 放进 [FocusGuardFrameLayout] 内，
 * 在保持所有 ViewTree Owner 绑定一致的前提下，彻底免疫卸载聚焦 AndroidView 时的焦点重入崩溃。
 */
internal fun ComponentActivity.setFocusGuardedContent(
    content: @Composable () -> Unit
) {
    val decorView = window.decorView
    if (decorView.findViewTreeLifecycleOwner() == null) {
        decorView.setViewTreeLifecycleOwner(this)
    }
    if (decorView.findViewTreeViewModelStoreOwner() == null) {
        decorView.setViewTreeViewModelStoreOwner(this)
    }
    if (decorView.findViewTreeSavedStateRegistryOwner() == null) {
        decorView.setViewTreeSavedStateRegistryOwner(this)
    }

    val contentRoot = decorView.findViewById<ViewGroup>(AndroidR.id.content)
    val existingGuard = contentRoot.getChildAt(0) as? FocusGuardFrameLayout
    val existingComposeView = existingGuard?.getChildAt(0) as? ComposeView
    if (existingComposeView != null) {
        existingComposeView.setContent(content)
        return
    }

    val composeView = ComposeView(this).apply {
        setContent(content)
    }
    val guard = FocusGuardFrameLayout(this).apply {
        addView(
            composeView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }
    setContentView(
        guard,
        ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    )
}
