package org.maiwithu.maidroid.webui

import kotlin.math.ceil

/**
 * 横屏时让 MaiBot WebUI 按桌面版排版：网页看到的布局视口至少 1040 CSS px 宽。
 *
 * 做法：按视图尺寸算出布局宽度后，经内置 WebExtension 原生端口通知 `content.js` 把页面 viewport meta
 * 改成 `width=布局宽度`，GeckoSession 打开 `VIEWPORT_MODE_MOBILE` 后会按该宽度缩放整页，
 * WebUI 的响应式断点自然切换到桌面布局；竖屏不改，仍按手机版布局。
 *
 * 下限的来历：1024 是 WebUI 桌面布局的断点（min-width: 1024px，左侧边栏从这里出现），
 * 但刚过断点时首页 KEYS 卡片的 6 个快捷按钮要布局宽 1030 以上才排得下两列，所以宽度下限取 1040 留出余量。
 */
internal object WebUiDesktopViewport {
    /** 桌面版布局视口的最小宽度（CSS px）：WebUI 桌面布局断点 1024 再留余量，避开首页卡片刚过断点时被挤坏。 */
    const val MIN_WIDTH_CSS = 1040

    /**
     * 按视图物理像素尺寸算出桌面版的布局宽度（CSS px）；竖屏（宽不大于高）或还没量出尺寸时返回 null。
     * 手机横屏不够宽就缩到最小宽度，大屏本来就够宽时保持原生 CSS 宽度不放大。
     */
    fun layoutWidthCss(widthPx: Int, heightPx: Int, density: Float): Int? {
        if (widthPx <= 0 || heightPx <= 0 || widthPx <= heightPx) return null

        val naturalWidth = ceil(widthPx / density.coerceAtLeast(MIN_DENSITY).toDouble()).toInt()
        return maxOf(MIN_WIDTH_CSS, naturalWidth)
    }

    private const val MIN_DENSITY = 0.1f
}
