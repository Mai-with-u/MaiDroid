package org.maiwithu.maidroid.webui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.log.TerminalLog

object MaiBotWebUiSupport {
    private const val TAG = "MaiBotWebUiSupport"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val tokenLogRegex = Regex("""(?i)WebUI[^\r\n]*Token:\s*([^\s]+)""")
    private val firstScriptTagRegex = Regex("""<script(?:\s|>)""", RegexOption.IGNORE_CASE)
    private val closingHeadTagRegex = Regex("""</head>""", RegexOption.IGNORE_CASE)

    // 只匹配样式声明里的 env(safe-area-inset-bottom)；Tailwind 类名选择器里的括号带反斜杠转义，不会被误匹配。
    private val bottomSafeAreaRegex =
        Regex("""env\(\s*safe-area-inset-bottom\s*(?:,[^()]*)?\)""", RegexOption.IGNORE_CASE)

    // WebUI 只跑在本机回环地址上，样式表改写只针对这些主机。
    private val loopbackHosts = setOf("127.0.0.1", "localhost", "[::1]")

    fun enableCookies(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }
    }

    fun resolveLaunchUrl(
        context: Context,
        baseUrl: String = MaiBotWebUi.URL,
        terminalLogs: List<String> = TerminalLog.logs.value
    ): String {
        val token = findAccessToken(context, terminalLogs) ?: return baseUrl
        return buildAuthUrl(baseUrl, token)
    }

    fun findAccessToken(context: Context, terminalLogs: List<String>): String? =
        readAccessTokenFromConfig(context) ?: extractAccessTokenFromLogs(terminalLogs)

    fun extractAccessTokenFromLogs(terminalLogs: List<String>): String? =
        terminalLogs.asReversed().firstNotNullOfOrNull { line ->
            tokenLogRegex.find(line)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

    fun redactUrlForLogs(url: String): String =
        runCatching {
            val uri = Uri.parse(url)
            if (uri.getQueryParameter("token") == null) {
                url
            } else {
                uri.buildUpon()
                    .clearQuery()
                    .appendQueryParameter("token", "<redacted>")
                    .build()
                    .toString()
            }
        }.getOrElse {
            url.replace(Regex("""token=[^&#]+"""), "token=<redacted>")
        }

    /**
     * 拦截 WebUI 的请求并改写内容。
     * 触发条件：主框架 HTML 导航，或底部安全区高度大于 0 时 WebUI 自己的 .css 样式表请求。
     * 执行顺序：HTML 注入主题和视口修补脚本；样式表把 env(safe-area-inset-bottom) 加上 APP 导航栏占高。
     * 原因：导航栏浮在 WebView 上，网页的 viewport 又没有 viewport-fit=cover，env 恒为 0，
     * 上游贴底的悬浮底栏会被导航栏盖住；把导航栏占高并进安全区后，上游所有贴底元素会一起上移。
     */
    fun interceptAndInjectTheme(
        request: WebResourceRequest,
        logTag: String = TAG,
        bottomInsetCssPx: Int = 0
    ): WebResourceResponse? {
        val isHtml = shouldInterceptHtmlRequest(request)
        val isStylesheet = !isHtml && bottomInsetCssPx > 0 && shouldInterceptStylesheetRequest(request)
        if (!isHtml && !isStylesheet) return null

        val url = request.url?.toString() ?: return null
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5_000
                readTimeout = 5_000
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
                injectThemeBootstrap(body)
            } else {
                rewriteBottomSafeArea(body, bottomInsetCssPx)
            }
            WebResourceResponse(
                mimeType,
                Charsets.UTF_8.name(),
                ByteArrayInputStream(modifiedBody.toByteArray(Charsets.UTF_8))
            ).apply {
                if (isHtml && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
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

    fun patchViewportLayout(
        webView: WebView,
        reservedBottomCssPx: Int = 0,
        logTag: String = TAG
    ) {
        webView.evaluateJavascript(
            WebUiInjectedScripts.viewportPatch(reservedBottomCssPx)
        ) { result ->
            Log.d(logTag, "viewport patch $result")
        }
    }

    private fun readAccessTokenFromConfig(context: Context): String? {
        val tokenFile = RuntimePaths.from(context).maiBotWebUiConfig
        if (!tokenFile.isFile) return null

        return runCatching {
            val root = json.parseToJsonElement(tokenFile.readText(Charsets.UTF_8)).jsonObject
            root["access_token"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to read WebUI access token from ${tokenFile.absolutePath}: ${error.message}")
            null
        }
    }

    private fun buildAuthUrl(baseUrl: String, token: String): String =
        Uri.parse(baseUrl)
            .buildUpon()
            .encodedPath("/auth")
            .clearQuery()
            .fragment(null)
            .appendQueryParameter("token", token)
            .build()
            .toString()

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cookieManager.flush()
        }
    }

    /** 在页面第一个脚本之前注入主题和视口单位修补脚本，保证它们先于页面自己的脚本和首次渲染执行。 */
    internal fun injectThemeBootstrap(html: String): String {
        val bootstrap = WebUiInjectedScripts.pageBootstrap()

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

open class MaiBotWebViewClient(
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
            // 导航栏占高同时作为网页的底部安全区，样式表请求据此改写 env(safe-area-inset-bottom)
            MaiBotWebUiSupport.interceptAndInjectTheme(
                request = it,
                logTag = logTag,
                bottomInsetCssPx = viewportReservedBottomCssPx
            )
        }

    override fun onPageFinished(view: WebView, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
        }
        MaiBotWebUiSupport.patchViewportLayout(
            webView = view,
            reservedBottomCssPx = viewportReservedBottomCssPx,
            logTag = logTag
        )
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
