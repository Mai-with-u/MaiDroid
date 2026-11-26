package org.maiwithu.maidroid

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Display
import android.view.View
import kotlin.math.abs

/** 单个物理显示模式的关键参数，抽成纯数据模型便于单测覆盖筛选逻辑。 */
internal data class DisplayRefreshModeSpec(
    val modeId: Int,
    val physicalWidth: Int,
    val physicalHeight: Int,
    val refreshRate: Float
)

/** 选出的高刷新率显示模式编号与目标帧率（Hz）。 */
internal data class DisplayRefreshModeSelection(
    val modeId: Int,
    val refreshRate: Float
)

private const val HIGH_REFRESH_RATE_THRESHOLD_HZ = 61f
private const val REFRESH_RATE_EPSILON_HZ = 0.1f

/**
 * 在保持当前物理分辨率不变的前提下，挑选支持列表里最高的刷新率模式；
 * 仅当峰值超过 61Hz 时返回选中结果，纯 60Hz 屏幕保持系统默认策略不变。
 */
internal fun selectPreferredHighRefreshMode(
    currentWidth: Int,
    currentHeight: Int,
    modes: List<DisplayRefreshModeSpec>
): DisplayRefreshModeSelection? {
    if (currentWidth <= 0 || currentHeight <= 0) return null
    val best = modes
        .asSequence()
        .filter { it.physicalWidth == currentWidth && it.physicalHeight == currentHeight }
        .maxByOrNull { it.refreshRate }
        ?: return null
    if (best.refreshRate <= HIGH_REFRESH_RATE_THRESHOLD_HZ) return null
    return DisplayRefreshModeSelection(
        modeId = best.modeId,
        refreshRate = best.refreshRate
    )
}

/** 沿着 ContextWrapper 链向上解析宿主 Activity；非 Activity 上下文返回 null。 */
internal fun resolveActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 为窗口声明当前分辨率下的最高可用刷新率（如 120Hz）：
 * Android 15+ 与部分厂商动态刷新率调度会在未声明高刷请求时把嵌入渲染视图压到 60Hz，
 * 显式声明同分辨率最高模式可让 Compose 动效与 GeckoView 网页滚动同步跑满高刷。
 */
internal fun applyPreferredHighRefreshRate(activity: Activity): Float? {
    val display = resolveWindowDisplay(activity) ?: return null
    val currentMode = display.mode ?: return null
    val specs = display.supportedModes.map { mode ->
        DisplayRefreshModeSpec(
            modeId = mode.modeId,
            physicalWidth = mode.physicalWidth,
            physicalHeight = mode.physicalHeight,
            refreshRate = mode.refreshRate
        )
    }
    val selection = selectPreferredHighRefreshMode(
        currentWidth = currentMode.physicalWidth,
        currentHeight = currentMode.physicalHeight,
        modes = specs
    ) ?: return null

    val window = activity.window
    val attrs = window.attributes
    if (attrs.preferredDisplayModeId != selection.modeId ||
        abs(attrs.preferredRefreshRate - selection.refreshRate) > REFRESH_RATE_EPSILON_HZ
    ) {
        attrs.preferredDisplayModeId = selection.modeId
        attrs.preferredRefreshRate = selection.refreshRate
        window.attributes = attrs
    }
    return selection.refreshRate
}

/**
 * Android 15（API 35+）自适应刷新率框架会为内嵌渲染视图单独投票；
 * 为 GeckoView 声明高刷新率偏好，避免网页浏览与惯性滚动被默认降至 60Hz。
 */
internal fun requestHighFrameRateOnView(view: View, refreshRateHz: Float?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val requestedRate = if (refreshRateHz != null && refreshRateHz > HIGH_REFRESH_RATE_THRESHOLD_HZ) {
        refreshRateHz
    } else {
        View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
    }
    view.setRequestedFrameRate(requestedRate)
}

private fun resolveWindowDisplay(activity: Activity): Display? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        activity.display
    } else {
        @Suppress("DEPRECATION")
        activity.windowManager.defaultDisplay
    }
