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
import org.maiwithu.maidroid.container.MaiBotContainerConfig
import org.maiwithu.maidroid.process.TerminalLogRepository

object MaiBotWebUiSupport {
    private const val TAG = "MaiBotWebUiSupport"
    private const val WEBUI_CONFIG_PATH = "data/webui.json"
    private const val THEME_MODE = "dark"
    private const val DASHBOARD_STYLE = "future-retro"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val tokenLogRegex = Regex("""(?i)WebUI[^\r\n]*Token:\s*([^\s]+)""")
    private val firstScriptTagRegex = Regex("""<script(?:\s|>)""", RegexOption.IGNORE_CASE)
    private val closingHeadTagRegex = Regex("""</head>""", RegexOption.IGNORE_CASE)

    private val themeBootstrapScript = """
        <script>
        (function() {
          try {
            localStorage.setItem('maibot-theme-mode', '$THEME_MODE');
            localStorage.setItem('maibot-theme-dashboard-style', '$DASHBOARD_STYLE');
          } catch (error) {}
        })();
        </script>
    """.trimIndent()

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
        baseUrl: String = MaiBotContainerConfig.WEB_UI_URL,
        terminalLogs: List<String> = TerminalLogRepository.logs.value
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

    fun interceptAndInjectTheme(
        request: WebResourceRequest,
        logTag: String = TAG
    ): WebResourceResponse? {
        if (!shouldInterceptHtmlRequest(request)) return null

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

            val contentType = connection.contentType.orEmpty()
            if (contentType.isNotBlank() && !contentType.contains("text/html", ignoreCase = true)) {
                return null
            }

            persistResponseCookies(url, connection)

            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val modifiedHtml = injectThemeBootstrap(html)
            WebResourceResponse(
                "text/html",
                Charsets.UTF_8.name(),
                ByteArrayInputStream(modifiedHtml.toByteArray(Charsets.UTF_8))
            ).apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    responseHeaders = mapOf("Cache-Control" to "no-cache")
                }
            }
        } catch (error: Exception) {
            Log.w(logTag, "Theme HTML intercept failed for ${request.url?.path.orEmpty()}: ${error.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readAccessTokenFromConfig(context: Context): String? {
        val config = MaiBotContainerConfig.from(context)
        val tokenFile = File(config.maiBotDir, WEBUI_CONFIG_PATH)
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

    private fun injectThemeBootstrap(html: String): String {
        firstScriptTagRegex.find(html)?.let { match ->
            return html.substring(0, match.range.first) +
                themeBootstrapScript +
                html.substring(match.range.first)
        }

        closingHeadTagRegex.find(html)?.let { match ->
            return html.substring(0, match.range.first) +
                themeBootstrapScript +
                html.substring(match.range.first)
        }

        return themeBootstrapScript + html
    }
}

open class MaiBotWebViewClient(
    private val logTag: String,
    private val onPageFinishedCallback: (WebView, String) -> Unit = { _, _ -> },
    private val onMainFrameError: (WebResourceRequest, WebResourceError) -> Unit = { _, _ -> }
) : WebViewClient() {
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? =
        request?.let { MaiBotWebUiSupport.interceptAndInjectTheme(it, logTag) }

    override fun onPageFinished(view: WebView, url: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
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
