package org.maiwithu.maidroid.webui

import android.webkit.WebView

/**
 * WebUI 对新版前端特性的最低推荐 Chromium 主版本：
 * 新版 WebUI 前端使用的 Tailwind v4（`@layer` 需 99+、`oklch()` 需 111+）以及 `AbortSignal.timeout`（103+）。
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
