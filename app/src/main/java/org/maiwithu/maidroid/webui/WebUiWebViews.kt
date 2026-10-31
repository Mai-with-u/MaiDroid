package org.maiwithu.maidroid.webui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 创建加载 MaiBot WebUI / NapCat 管理页用的 WebView，全屏 WebUiActivity 和主页的 WebUI 页签共用：
 * 开 JS 和 DOM 存储（两个 WebUI 都是单页应用）-> 允许网页按 viewport 缩放（横屏桌面版靠它），但不开双指缩放和缩放按钮 ->
 * 允许混合内容（页面走 http 回环地址）-> 接受 Cookie（登录态存在 Cookie 里）-> 黑底，避免加载时闪白 ->
 * 装上两指滚动（见 WebUiTwoFingerScroll）：两指上下滑直接滚外层页面，不被子滚动块拦走 ->
 * 接上 downloads（不接的话网页的导出、下载按钮点了没反应）。
 * 页面拦截、注入、错误回调由调用方设置的 WebUiWebViewClient 负责。
 */
// 两指滚动的触摸监听不产生点击，单指点击仍由网页自己处理，不需要 performClick。
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
internal fun createWebUiWebView(context: Context, downloads: WebUiDownloads): WebView {
    val webView = WebView(context)
    webView.setBackgroundColor(Color.BLACK)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        setSupportZoom(true)
        builtInZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.setOnTouchListener(WebUiTwoFingerScroll(webView))
    downloads.attachTo(webView)
    return webView
}
