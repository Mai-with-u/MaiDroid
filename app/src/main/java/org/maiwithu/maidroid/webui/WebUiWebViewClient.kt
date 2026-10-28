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

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? =
        request?.let {
            WebUiResponseRewriter.intercept(
                request = it,
                logTag = logTag,
                bottomInsetCssPx = viewportReservedBottomCssPx
            )
        }

    override fun onPageFinished(view: WebView, url: String) {
        CookieManager.getInstance().flush()
        view.evaluateJavascript(WebUiInjectedScripts.viewportPatch(viewportReservedBottomCssPx)) { result ->
            Log.d(logTag, "viewport patch $result")
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
