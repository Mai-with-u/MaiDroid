package org.maiwithu.maidroid.webui

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 拦截 MaiBot WebUI 的页面 HTML，在 APP 侧改写后再交给 WebView：在第一个脚本之前注入
 * WebUiInjectedScripts.pageBootstrap（主题 + vh 兜底 + 下载拦截）；横屏桌面版时再把 viewport meta 换成
 * WebUiDesktopViewport 算出的布局宽度。其余请求（样式表、脚本、图片、接口、第三方资源）原样放行。由 WebUiWebViewClient 调用。
 */
internal object WebUiResponseRewriter {
    private const val TAG = "WebUiResponseRewriter"
    private const val FETCH_TIMEOUT_MS = 5_000

    private val firstScriptTagRegex = Regex("""<script(?:\s|>)""", RegexOption.IGNORE_CASE)
    private val closingHeadTagRegex = Regex("""</head>""", RegexOption.IGNORE_CASE)
    private val viewportMetaRegex =
        Regex("""<meta\b[^>]*\bname\s*=\s*["']?viewport["']?[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * 拦截 WebUI 的页面 HTML 并改写内容。
     * 触发条件：主框架 HTML 导航（根路径、.html 或单页应用的前端路由）。
     * 执行顺序：自己发请求取回 HTML -> 落盘响应里的 Cookie -> 注入主题、vh 兜底和下载拦截脚本
     * （desktopLayoutWidthCss 不为空时同时改写 viewport 宽度）-> 交给 WebView。取不到或不是 HTML 时交回 WebView 自己加载。
     */
    fun intercept(
        request: WebResourceRequest,
        logTag: String = TAG,
        desktopLayoutWidthCss: Int? = null
    ): WebResourceResponse? {
        if (!shouldInterceptHtmlRequest(request)) return null

        val url = request.url?.toString() ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = FETCH_TIMEOUT_MS
                readTimeout = FETCH_TIMEOUT_MS
                instanceFollowRedirects = true
                request.requestHeaders["User-Agent"]?.let { setRequestProperty("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { setRequestProperty("Cookie", it) }
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) return null

            // 响应不是 HTML 时（比如前端路由命中了下载接口），放回给 WebView 自己处理
            val contentType = connection.contentType.orEmpty()
            if (contentType.isNotBlank() && !contentType.contains("text/html", ignoreCase = true)) {
                return null
            }

            persistResponseCookies(url, connection)

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            WebResourceResponse(
                "text/html",
                Charsets.UTF_8.name(),
                ByteArrayInputStream(injectPageBootstrap(body, desktopLayoutWidthCss).toByteArray(Charsets.UTF_8))
            ).apply {
                responseHeaders = mapOf("Cache-Control" to "no-cache")
            }
        } catch (error: Exception) {
            Log.w(logTag, "WebUI intercept failed for ${request.url?.path.orEmpty()}: ${error.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    // 主框架的 GET 导航，且路径是根、.html 或不带扩展名（单页应用的前端路由）才算页面 HTML。
    private fun shouldInterceptHtmlRequest(request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame || !request.method.equals("GET", ignoreCase = true)) {
            return false
        }

        val uri = request.url ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false

        val path = uri.path.orEmpty()
        if (path.isEmpty() || path == "/" || path.endsWith(".html", ignoreCase = true)) {
            return true
        }

        val lastSegment = path.substringAfterLast('/')
        return !lastSegment.contains('.')
    }

    private fun persistResponseCookies(url: String, connection: HttpURLConnection) {
        val cookieManager = CookieManager.getInstance()
        connection.headerFields
            ?.filterKeys { it?.equals("Set-Cookie", ignoreCase = true) == true }
            ?.values
            ?.flatten()
            ?.forEach { cookieManager.setCookie(url, it) }
        cookieManager.flush()
    }

    /**
     * 在页面第一个脚本之前注入主题和视口单位修补脚本，保证它们先于页面自己的脚本和首次渲染执行。
     * desktopLayoutWidthCss 不为空（横屏桌面版）时，先把页面的 viewport meta 整个换成指定宽度；
     * 页面没写 viewport meta 就跟着注入的脚本补一个。直接改 HTML 文本而不是等脚本改 DOM，
     * 是为了让网页第一次排版就按桌面宽度来，不会先闪一下手机版。
     */
    internal fun injectPageBootstrap(html: String, desktopLayoutWidthCss: Int? = null): String {
        var page = html
        var bootstrap = WebUiInjectedScripts.pageBootstrap()
        if (desktopLayoutWidthCss != null) {
            val desktopViewport = WebUiDesktopViewport.viewportMetaTag(desktopLayoutWidthCss)
            if (viewportMetaRegex.containsMatchIn(page)) {
                page = viewportMetaRegex.replace(page) { desktopViewport }
            } else {
                bootstrap = desktopViewport + bootstrap
            }
        }
        return insertBootstrap(page, bootstrap)
    }

    private fun insertBootstrap(html: String, bootstrap: String): String {
        firstScriptTagRegex.find(html)?.let { match ->
            return html.substring(0, match.range.first) +
                bootstrap +
                html.substring(match.range.first)
        }

        closingHeadTagRegex.find(html)?.let { match ->
            return html.substring(0, match.range.first) +
                bootstrap +
                html.substring(match.range.first)
        }

        return bootstrap + html
    }
}
