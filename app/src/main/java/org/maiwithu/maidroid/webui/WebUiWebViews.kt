package org.maiwithu.maidroid.webui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * 创建加载 MaiBot WebUI / NapCat 管理页用的 WebView，全屏 WebUiActivity 和主页的 WebUI 页签共用：
 * 开 JS 和 DOM 存储（两个 WebUI 都是单页应用）-> 允许缩放手势但不显示缩放按钮 ->
 * 允许混合内容（页面走 http 回环地址）-> 接受 Cookie（登录态存在 Cookie 里）-> 黑底，避免加载时闪白。
 * 页面拦截、注入、错误回调由调用方设置的 MaiBotWebViewClient 负责。
 */
@SuppressLint("SetJavaScriptEnabled")
internal fun createWebUiWebView(context: Context): WebView {
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
    return webView
}
