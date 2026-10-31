package org.maiwithu.maidroid

import android.view.Window
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 按屏幕方向设置系统栏，MainActivity 和 WebUiActivity 共用。
 *
 * - 横屏：状态栏和导航栏都隐藏，给页面让出整块高度（手机横过来本来就矮，状态栏还要占掉一成多）；
 * - 竖屏：状态栏常显，hideNavigationBar 为 true 时只隐藏导航栏（主页），否则系统栏全显示（OOBE、全屏网页）。
 *
 * 隐藏的系统栏都能从屏幕边缘滑动临时呼出，过一会儿自动收起，呼出时盖在页面上、不挤动页面。
 */
internal fun applySystemBars(window: Window, landscape: Boolean, hideNavigationBar: Boolean) {
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    when {
        landscape -> controller.hide(WindowInsetsCompat.Type.systemBars())
        hideNavigationBar -> {
            controller.show(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
        else -> controller.show(WindowInsetsCompat.Type.systemBars())
    }
}
