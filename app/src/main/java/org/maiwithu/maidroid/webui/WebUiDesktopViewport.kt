package org.maiwithu.maidroid.webui

import kotlin.math.ceil

/**
 * 横屏时让 MaiBot WebUI 按桌面版排版：网页看到的布局视口至少 1280×720 CSS px。
 *
 * 做法：拦截页面 HTML 时把 viewport meta 改成 width=布局宽度 -> WebView 打开 useWideViewPort 和总览模式，
 * 按这个宽度把整页缩到 WebView 里。相当于调低了这块 WebView 的 CSS 像素密度：
 * WebUI 的响应式断点（min-width: 1024px 等）看到的是放大后的视口，自然切到桌面布局。
 * 竖屏不改，网页仍按 width=device-width 走手机版。
 * 下限先定过 1600×900，真机上字太小，改成 1280×720：手机横屏全屏时大约 1.5 个物理像素对一个 CSS 像素。
 */
internal object WebUiDesktopViewport {
    /** 桌面版布局视口的最小宽度（CSS px）。 */
    const val MIN_WIDTH_CSS = 1280

    /** 桌面版布局视口的最小高度（CSS px）。 */
    const val MIN_HEIGHT_CSS = 720

    /**
     * 按 WebView 的物理像素尺寸算出桌面版的布局宽度（CSS px）；竖屏（宽不大于高）或还没量出尺寸时返回 null。
     *
     * 布局宽度定下后，整页按“WebView 物理宽 ÷ 布局宽”缩放，布局高 = 物理高 × 布局宽 ÷ 物理宽。
     * 所以取三者中最大的：最小宽度；让布局高也够最小高度所需的宽度；WebView 本来的 CSS 宽度（大屏本来就够，不把网页放大）。
     */
    fun layoutWidthCss(widthPx: Int, heightPx: Int, density: Float): Int? {
        if (widthPx <= 0 || heightPx <= 0 || widthPx <= heightPx) return null

        val widthForMinHeight = ceil(MIN_HEIGHT_CSS.toDouble() * widthPx / heightPx).toInt()
        val naturalWidth = ceil(widthPx / density.coerceAtLeast(MIN_DENSITY).toDouble()).toInt()
        return maxOf(MIN_WIDTH_CSS, widthForMinHeight, naturalWidth)
    }

    /** 改写后的 viewport meta：只给宽度，初始缩放交给 WebView 的总览模式按宽度铺满。 */
    fun viewportMetaTag(layoutWidthCss: Int): String =
        """<meta name="viewport" content="width=$layoutWidthCss">"""

    /**
     * 页面已经加载后改布局宽度用的脚本：找到（没有就补上）viewport meta 改成新宽度，浏览器会就地重排、重新按宽度铺满，
     * 不用重新加载。稍等一会儿再把网页实际拿到的布局视口打到 console（经 WebUiChromeClient 进 logcat），方便核对是否够下限。
     */
    fun updateViewportScript(layoutWidthCss: Int): String = """
        (() => {
          let meta = document.querySelector('meta[name="viewport"]');
          if (!meta) {
            meta = document.createElement('meta');
            meta.name = 'viewport';
            document.head.appendChild(meta);
          }
          meta.setAttribute('content', 'width=$layoutWidthCss');
          setTimeout(() => console.log('maidroid desktop viewport ' + JSON.stringify({
            width: document.documentElement.clientWidth,
            height: document.documentElement.clientHeight
          })), 300);
        })()
    """.trimIndent()

    private const val MIN_DENSITY = 0.1f
}
