package org.maiwithu.maidroid.webui

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 加载 WebUI 用的 WebViewClient，主页的 WebUI 页签和全屏 WebUiActivity 共用；全屏打开的既可能是 MaiBot WebUI，
 * 也可能是 NapCat 管理页，所以不以 MaiBot 命名。
 * 请求经 WebUiResponseRewriter 改写 -> 页面加载完后落盘 Cookie 并执行视口修补脚本 -> 主框架加载失败时回调给界面显示重试条。
 *
 * viewportReservedBottomCssPx 是浮在网页上方的 APP 底部导航栏的占高（CSS px，等于 dp），
 * 同时用于改写样式表里的底部安全区和视口修补时给滚动区留白；全屏打开时为 0。
 */
internal class WebUiWebViewClient(
    private val logTag: String,
    private val viewportReservedBottomCssPx: Int = 0,
    private val onPageFinishedCallback: (WebView, String) -> Unit = { _, _ -> },
    private val onMainFrameError: (WebResourceRequest, WebResourceError) -> Unit = { _, _ -> }
) : WebViewClient() {
    /**
     * 横屏桌面版的布局宽度（CSS px），竖屏为 null。由宿主在 loadUrl 前按 WebView 尺寸设好（见 applyDesktopViewport），
     * 拦截线程据此改写页面的 viewport meta。
     */
    @Volatile
    var desktopLayoutWidthCss: Int? = null
        private set

    // 算当前布局宽度时 WebView 的物理宽度；宽度变了才重算。
    private var layoutBasisWidthPx = 0
    private var resizeListenerAttached = false

    /**
     * 加载页面前调用：按 WebView 当前尺寸决定这次按桌面版还是手机版排版。
     * 横屏 -> 记下桌面版布局宽度，打开 useWideViewPort（让 viewport meta 的宽度生效）和总览模式（按宽度缩到铺满）；
     * 竖屏 -> 两项都关，网页和以前一样按 WebView 的 CSS 宽度排版。
     * 同时开始盯 WebView 的宽度，加载后宽度再变就就地改写 viewport（见 onWebViewWidthChanged）。
     */
    fun applyDesktopViewport(webView: WebView, enabled: Boolean = true) {
        val layoutWidth = if (enabled) {
            WebUiDesktopViewport.layoutWidthCss(
                widthPx = webView.width,
                heightPx = webView.height,
                density = webView.resources.displayMetrics.density
            )
        } else {
            null
        }
        desktopLayoutWidthCss = layoutWidth
        layoutBasisWidthPx = webView.width
        webView.settings.useWideViewPort = layoutWidth != null
        webView.settings.loadWithOverviewMode = layoutWidth != null

        if (!resizeListenerAttached) {
            resizeListenerAttached = true
            webView.addOnLayoutChangeListener { view, left, _, right, _, oldLeft, _, oldRight, _ ->
                if (right - left != oldRight - oldLeft) onWebViewWidthChanged(view as WebView)
            }
        }
    }

    /**
     * 桌面版页面加载后 WebView 宽度变了：按新尺寸重算布局宽度，经脚本就地改写 viewport meta，不重新加载页面。
     * 触发场景：首帧排版时横屏挖孔的安全区还没生效，WebView 先按整屏宽度量了一次，下一帧才让出挖孔，
     * 照旧宽度排版的话网页会比需要的更小。只看宽度：键盘弹出只会压矮 WebView，这时不重算，网页不会在打字时突然缩小。
     */
    private fun onWebViewWidthChanged(webView: WebView) {
        if (desktopLayoutWidthCss == null || webView.width == layoutBasisWidthPx) return
        val updated = WebUiDesktopViewport.layoutWidthCss(
            widthPx = webView.width,
            heightPx = webView.height,
            density = webView.resources.displayMetrics.density
        ) ?: return
        layoutBasisWidthPx = webView.width
        if (updated == desktopLayoutWidthCss) return

        desktopLayoutWidthCss = updated
        webView.evaluateJavascript(WebUiDesktopViewport.updateViewportScript(updated), null)
        Log.d(logTag, "desktop viewport resized to width=$updated at ${webView.width}x${webView.height}")
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? =
        request?.let {
            WebUiResponseRewriter.intercept(
                request = it,
                logTag = logTag,
                bottomInsetCssPx = viewportReservedBottomCssPx,
                desktopLayoutWidthCss = desktopLayoutWidthCss
            )
        }

    override fun onPageFinished(view: WebView, url: String) {
        CookieManager.getInstance().flush()
        val desktopWidth = desktopLayoutWidthCss
        if (desktopWidth == null) {
            view.evaluateJavascript(WebUiInjectedScripts.viewportPatch(viewportReservedBottomCssPx)) { result ->
                Log.d(logTag, "viewport patch $result")
            }
        } else {
            // 桌面版按普通桌面浏览器排版：视口修补里的高度锁定是给手机版和键盘准备的，
            // 用在桌面版上，双指缩放时会把整页高度锁成放大后可见区域的高度，所以不注入。
            // 再按最新宽度写一次 viewport：页面请求发出后、加载完成前宽度变过的话，HTML 里还是旧宽度。
            view.evaluateJavascript(WebUiDesktopViewport.updateViewportScript(desktopWidth), null)
        }
        onPageFinishedCallback(view, url)
        super.onPageFinished(view, url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (request.isForMainFrame) {
            onMainFrameError(request, error)
        }
        super.onReceivedError(view, request, error)
    }
}
