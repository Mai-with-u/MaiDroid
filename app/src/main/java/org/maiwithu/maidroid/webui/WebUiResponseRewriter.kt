package org.maiwithu.maidroid.webui

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 拦截 MaiBot WebUI 的请求，在 APP 侧改写返回内容后再交给 WebView：
 * - 页面 HTML：在第一个脚本之前注入 WebUiInjectedScripts.pageBootstrap（主题 + vh 兜底）；
 *   横屏桌面版时再把 viewport meta 换成 WebUiDesktopViewport 算出的布局宽度；
 * - 本机 .css 样式表：把 env(safe-area-inset-bottom) 加上 APP 底部导航栏的占高。
 * 其余请求（脚本、图片、接口、第三方资源）原样放行。由 WebUiWebViewClient 调用。
 */
internal object WebUiResponseRewriter {
    private const val TAG = "WebUiResponseRewriter"
    private const val FETCH_TIMEOUT_MS = 5_000

    private val firstScriptTagRegex = Regex("""<script(?:\s|>)""", RegexOption.IGNORE_CASE)
    private val closingHeadTagRegex = Regex("""</head>""", RegexOption.IGNORE_CASE)
    private val viewportMetaRegex =
        Regex("""<meta\b[^>]*\bname\s*=\s*["']?viewport["']?[^>]*>""", RegexOption.IGNORE_CASE)

    // 只匹配样式声明里的 env(safe-area-inset-bottom)；Tailwind 类名选择器里的括号带反斜杠转义，不会被误匹配。
    private val bottomSafeAreaRegex =
        Regex("""env\(\s*safe-area-inset-bottom\s*(?:,[^()]*)?\)""", RegexOption.IGNORE_CASE)

    // WebUI 只跑在本机回环地址上，样式表改写只针对这些主机。
    private val loopbackHosts = setOf("127.0.0.1", "localhost", "[::1]")

    /**
     * 拦截 WebUI 的请求并改写内容。
     * 触发条件：主框架 HTML 导航，或底部安全区高度大于 0 时 WebUI 自己的 .css 样式表请求。
     * 执行顺序：HTML 注入主题和视口修补脚本（desktopLayoutWidthCss 不为空时同时改写 viewport 宽度）；
     * 样式表把 env(safe-area-inset-bottom) 加上 APP 导航栏占高。
     * 原因：导航栏浮在 WebView 上，网页的 viewport 又没有 viewport-fit=cover，env 恒为 0，
     * 上游贴底的悬浮底栏会被导航栏盖住；把导航栏占高并进安全区后，上游所有贴底元素会一起上移。
     */
    fun intercept(
        request: WebResourceRequest,
        logTag: String = TAG,
        bottomInsetCssPx: Int = 0,
        desktopLayoutWidthCss: Int? = null
    ): WebResourceResponse? {
        val isHtml = shouldInterceptHtmlRequest(request)
        val isStylesheet = !isHtml && bottomInsetCssPx > 0 && shouldInterceptStylesheetRequest(request)
        if (!isHtml && !isStylesheet) return null

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

            // 响应类型和请求种类对不上时（比如 .css 路径返回了 HTML 兜底页），放回给 WebView 自己处理
            val mimeType = if (isHtml) "text/html" else "text/css"
            val contentType = connection.contentType.orEmpty()
            if (contentType.isNotBlank() && !contentType.contains(mimeType, ignoreCase = true)) {
                return null
            }

            if (isHtml) persistResponseCookies(url, connection)

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val modifiedBody = if (isHtml) {
                injectPageBootstrap(body, desktopLayoutWidthCss)
            } else {
                rewriteBottomSafeArea(body, bottomInsetCssPx)
            }
            WebResourceResponse(
                mimeType,
                Charsets.UTF_8.name(),
                ByteArrayInputStream(modifiedBody.toByteArray(Charsets.UTF_8))
            ).apply {
                if (isHtml) {
                    responseHeaders = mapOf("Cache-Control" to "no-cache")
                }
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

    private fun shouldInterceptStylesheetRequest(request: WebResourceRequest): Boolean {
        if (!request.method.equals("GET", ignoreCase = true)) return false

        val uri = request.url ?: return false
        return isLocalStylesheetUrl(uri.scheme, uri.host, uri.path)
    }

    /** 判断是不是本机 WebUI 的 .css 样式表；字体、第三方站点的样式表不碰。 */
    internal fun isLocalStylesheetUrl(scheme: String?, host: String?, path: String?): Boolean {
        val normalizedScheme = scheme?.lowercase()
        if (normalizedScheme != "http" && normalizedScheme != "https") return false
        if (host?.lowercase() !in loopbackHosts) return false
        return path.orEmpty().endsWith(".css", ignoreCase = true)
    }

    /**
     * 给样式表里的 env(safe-area-inset-bottom) 加上 APP 导航栏占高。
     * 触发条件：拦截到本机 WebUI 的样式表，且导航栏占高大于 0。
     * 角色动作：原来贴着安全区定位的元素（底栏、对话框、批量操作条）变成 calc(env(...) + 占高)。
     * 执行顺序：逐处替换声明里的 env 写法 -> 其余文本原样保留。
     * 原因：上游用 env 表达“屏幕底部要避让的区域”，导航栏浮在 WebView 上，等价于这块区域，
     * 直接改样式文本比改 CSSOM 可靠（CSSOM 对含 env 的简写属性拿不到长属性值）。
     */
    internal fun rewriteBottomSafeArea(css: String, insetCssPx: Int): String {
        val inset = insetCssPx.coerceAtLeast(0)
        if (inset == 0) return css

        return bottomSafeAreaRegex.replace(css) { match -> "calc(${match.value} + ${inset}px)" }
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
