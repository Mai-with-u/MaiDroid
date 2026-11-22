package org.maiwithu.maidroid.webui

import android.webkit.WebView
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL

/**
 * WebUI 对新版前端特性的最低推荐 Chromium 主版本：
 * 1.4.8 内置前端使用 Tailwind v4（@layer 需 99+、oklch() 需 111+）以及 AbortSignal.timeout（103+）。
 */
internal const val MIN_RECOMMENDED_WEBVIEW_MAJOR = 111

/** 当前系统 WebView 实现包信息，用于诊断报告定位与旧版提示。 */
internal data class WebViewPackageInfo(
    val packageName: String,
    val versionName: String,
    val majorVersion: Int?
)

/** 从 WebView 版本号（如 `"132.0.6.304"`）开头提取主版本号；空白或非数字返回 null。 */
internal fun parseWebViewMajorVersion(versionName: String?): Int? {
    val trimmed = versionName?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    val digits = trimmed.takeWhile(Char::isDigit)
    return digits.toIntOrNull()
}

/** 读取系统当前正在使用的 WebView 包与版本，环境不可用或受限时返回 null。 */
internal fun currentWebViewInfo(): WebViewPackageInfo? = runCatching {
    val pkg = WebView.getCurrentWebViewPackage() ?: return null
    val versionName = pkg.versionName?.trim().orEmpty()
    WebViewPackageInfo(
        packageName = pkg.packageName,
        versionName = versionName.ifEmpty { "未知" },
        majorVersion = parseWebViewMajorVersion(versionName)
    )
}.getOrNull()

/**
 * 判断系统 WebView 是否低于推荐主版本：
 * 即使已注入 JS 兼容补丁让接口正常连通，过旧内核仍可能无法渲染 oklch/级联样式，提前给出升级指引。
 */
internal fun outdatedWebViewNotice(info: WebViewPackageInfo?): String? {
    val major = info?.majorVersion ?: return null
    if (major >= MIN_RECOMMENDED_WEBVIEW_MAJOR) return null
    return "当前系统 WebView 版本较旧（${info.versionName}），页面样式可能显示异常；建议在应用商店更新 WebView"
}

/** 判断目标主机是否指向本机回环地址，供本机服务直连绕过系统代理使用。 */
internal fun isLoopbackHost(host: String?): Boolean {
    val normalized = host?.trim()?.lowercase() ?: return false
    return normalized == "127.0.0.1" ||
        normalized == "localhost" ||
        normalized == "::1" ||
        normalized == "[::1]"
}

/**
 * 打开 HTTP 连接：回环地址强制直连（`Proxy.NO_PROXY`），防止用户开启系统 Wi-Fi / VPN HTTP 代理
 * 后本机 WebUI、HTML 注入或协议端接口被代理软件拦截导致连不上；其余外网地址沿用系统默认策略。
 */
internal fun openDirectOrDefault(url: URL): HttpURLConnection {
    val connection = if (isLoopbackHost(url.host)) {
        url.openConnection(Proxy.NO_PROXY)
    } else {
        url.openConnection()
    }
    return connection as HttpURLConnection
}
