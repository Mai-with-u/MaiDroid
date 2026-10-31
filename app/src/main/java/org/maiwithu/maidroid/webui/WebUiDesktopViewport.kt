package org.maiwithu.maidroid.webui

import kotlin.math.ceil

/**
 * 横屏时让 MaiBot WebUI 按桌面版排版：网页看到的布局视口至少 1040 CSS px 宽。
 *
 * 做法：拦截页面 HTML 时把 viewport meta 改成 width=布局宽度 -> WebView 打开 useWideViewPort 和总览模式，
 * 按这个宽度把整页缩到 WebView 里。相当于调低了这块 WebView 的 CSS 像素密度：
 * WebUI 的响应式断点看到的是放大后的视口，自然切到桌面布局。
 * 竖屏不改，网页仍按 width=device-width 走手机版。
 *
 * 下限的来历：真机上试过 1600×900、1280×720，字都偏小；1024 是 WebUI 桌面布局的断点（min-width: 1024px，
 * 左侧边栏从这里出现），再窄就退回手机版的底部标签栏。但刚过断点时首页 KEYS 卡片的 6 个快捷按钮
 * 要布局宽 1030 以上才排得下两列，1024 时挤成一列冲出卡片，所以宽度下限取 1040，留出余量。
 * 高度不设下限：WebUI 没有按高度切换的断点，矮了只是多滚动；要是为了凑高度再把宽度撑大，细长的手机上字会更小。
 * 调试机横屏实际是 1040×568，大约 1.9 个物理像素对一个 CSS 像素。
 */
internal object WebUiDesktopViewport {
    /** 桌面版布局视口的最小宽度（CSS px）：WebUI 桌面布局的断点 1024 再留一点余量，避开首页卡片刚过断点时被挤坏。 */
    const val MIN_WIDTH_CSS = 1040

    /**
     * 按 WebView 的物理像素尺寸算出桌面版的布局宽度（CSS px）；竖屏（宽不大于高）或还没量出尺寸时返回 null。
     *
     * 布局宽度定下后，整页按“WebView 物理宽 ÷ 布局宽”缩放，布局高 = 物理高 × 布局宽 ÷ 物理宽。
     * 取最小宽度和 WebView 本来的 CSS 宽度中较大的：手机横屏不够宽就缩到最小宽度，大屏本来就够宽，不把网页放大。
     */
    fun layoutWidthCss(widthPx: Int, heightPx: Int, density: Float): Int? {
        if (widthPx <= 0 || heightPx <= 0 || widthPx <= heightPx) return null

        val naturalWidth = ceil(widthPx / density.coerceAtLeast(MIN_DENSITY).toDouble()).toInt()
        return maxOf(MIN_WIDTH_CSS, naturalWidth)
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
