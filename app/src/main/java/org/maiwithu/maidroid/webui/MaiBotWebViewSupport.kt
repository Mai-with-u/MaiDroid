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
    private const val VIEWPORT_PATCH_STYLE_ID = "maidroid-webview-viewport-fix"

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

    /**
     * 修复部分 WebView（如荣耀自带的 WebView）主框架里 vh/dvh/svh/lvh 恒为 0 的问题。
     * 触发条件：页面头部脚本执行时，用探针元素量到 100vh 的高度小于 1px。
     * 执行顺序：先把可视视口高度的 1% 写进 --maidroid-vh -> 再把已加载样式表里所有 vh 类单位
     * 改写成 calc(var(--maidroid-vh) * N) -> 之后监听新插入的样式表和视口变化继续补写。
     * 原因：新版 MaiBot 控制台外层容器用 h-[100dvh]，vh 为 0 时整页高度塌成 0，页面内容存在但画不出来。
     * 视口本身正常的设备探针量得出高度，脚本直接退出，不改动任何样式。
     */
    private val viewportUnitFixScript = """
        <script>
        (function() {
          try {
            // 探针：量 100vh 是否真的有高度，有高度说明这台 WebView 的 vh 正常，无需修补
            var probe = document.createElement('div');
            probe.style.cssText = 'position:absolute;left:-9999px;top:0;width:1px;height:100vh;visibility:hidden;pointer-events:none';
            document.documentElement.appendChild(probe);
            var measured = probe.getBoundingClientRect().height;
            probe.parentNode.removeChild(probe);
            if (measured >= 1 || window.__maidroidVhFixInstalled) return;
            window.__maidroidVhFixInstalled = true;

            // 把可视视口高度的 1% 写进根元素变量，键盘弹出等视口变化时同步更新
            var rootStyle = document.documentElement.style;
            var applyUnit = function() {
              var height = Math.round(
                (window.visualViewport && window.visualViewport.height) ||
                window.innerHeight ||
                document.documentElement.clientHeight ||
                0
              );
              if (height > 0) rootStyle.setProperty('--maidroid-vh', (height / 100) + 'px');
            };
            applyUnit();
            window.addEventListener('resize', applyUnit, { passive: true });
            if (window.visualViewport) {
              window.visualViewport.addEventListener('resize', applyUnit, { passive: true });
            }

            // 逐条改写样式表：只改声明值，不碰选择器（Tailwind 类名里也带 100dvh 字样）
            var hasUnit = /(?:^|[^\w-])-?\d*\.?\d+(?:dvh|svh|lvh|vh)\b/;
            var unitPattern = /(-?\d*\.?\d+)(?:dvh|svh|lvh|vh)\b/g;
            var toCalc = function(match, amount) {
              return 'calc(var(--maidroid-vh, 1px) * ' + amount + ')';
            };
            var seen = new WeakSet();
            var walk = function(rules) {
              for (var i = 0; i < rules.length; i++) {
                var rule = rules[i];
                var style = rule.style;
                if (style) {
                  for (var j = 0; j < style.length; j++) {
                    var name = style[j];
                    var value = style.getPropertyValue(name);
                    if (hasUnit.test(value)) {
                      style.setProperty(name, value.replace(unitPattern, toCalc), style.getPropertyPriority(name));
                    }
                  }
                }
                if (rule.cssRules) walk(rule.cssRules);
              }
            };
            var scan = function() {
              for (var i = 0; i < document.styleSheets.length; i++) {
                var sheet = document.styleSheets[i];
                if (seen.has(sheet)) continue;
                seen.add(sheet);
                try { walk(sheet.cssRules); } catch (error) {}
              }
            };

            // 样式表是异步加载的：link 加载完成、或有新的 style/link 插入时再扫一遍
            document.addEventListener('load', function(event) {
              if (event.target && event.target.tagName === 'LINK') scan();
            }, true);
            var scheduled = false;
            new MutationObserver(function() {
              if (scheduled) return;
              scheduled = true;
              requestAnimationFrame(function() { scheduled = false; scan(); });
            }).observe(document.documentElement, { childList: true, subtree: true });
            scan();
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

    fun patchViewportLayout(
        webView: WebView,
        reservedBottomCssPx: Int = 0,
        logTag: String = TAG
    ) {
        webView.evaluateJavascript(
            buildViewportPatchScript(reservedBottomCssPx)
        ) { result ->
            Log.d(logTag, "viewport patch $result")
        }
    }

    internal fun buildViewportPatchScript(reservedBottomCssPx: Int): String {
        val safeReservedBottom = reservedBottomCssPx.coerceAtLeast(0)
        return """
            (() => {
              const reservedBottom = $safeReservedBottom;
              const styleId = '$VIEWPORT_PATCH_STYLE_ID';

              const readViewportHeight = () => {
                const visualHeight = window.visualViewport && window.visualViewport.height;
                return Math.max(
                  1,
                  Math.round(
                    visualHeight ||
                    window.innerHeight ||
                    document.documentElement.clientHeight ||
                    document.body.clientHeight ||
                    1
                  )
                );
              };

              const ensureStyle = () => {
                let style = document.getElementById(styleId);
                if (!style) {
                  style = document.createElement('style');
                  style.id = styleId;
                  document.head.appendChild(style);
                }

                style.textContent = [
                  'html,body,#root{width:100%!important;height:var(--maidroid-webview-height)!important;min-height:var(--maidroid-webview-height)!important;overflow:auto!important;overscroll-behavior:none!important}',
                  '.min-h-screen{min-height:var(--maidroid-webview-height)!important}',
                  '.h-screen{height:var(--maidroid-webview-height)!important}',
                  '[data-maidroid-scroll-safe-area="true"]{scroll-padding-bottom:calc(var(--maidroid-webview-reserved-bottom) + 1rem)!important;padding-bottom:calc(var(--maidroid-original-padding-bottom, 0px) + var(--maidroid-webview-reserved-bottom) + 1rem)!important;box-sizing:border-box!important}',
                  '[class~="min-h-screen"][class~="overflow-y-auto"],[class~="h-screen"][class~="overflow-y-auto"],[class~="overflow-y-auto"],[class~="overflow-auto"],[class~="overscroll-contain"]{max-height:var(--maidroid-webview-height)!important;overflow-y:auto!important;-webkit-overflow-scrolling:touch!important}',
                  '[class~="min-h-screen"][class~="items-center"][class~="justify-center"][class~="overflow-y-auto"],[class~="h-screen"][class~="items-center"][class~="justify-center"][class~="overflow-y-auto"]{justify-content:flex-start!important}',
                  '[role="dialog"],[data-radix-dialog-content],[class*="DialogContent"],[class*="dialog-content"],[class*="modal-content"],[class*="drawer"],[class*="Drawer"],[class*="sidebar"],[class*="Sidebar"]{max-height:calc(var(--maidroid-webview-height) - var(--maidroid-webview-reserved-bottom) - 1rem)!important;overflow-y:auto!important;-webkit-overflow-scrolling:touch!important}',
                  '[class~="max-h-screen"]{max-height:calc(var(--maidroid-webview-height) - var(--maidroid-webview-reserved-bottom) - 1rem)!important}'
                ].join('\n');
              };

              const scrollCandidatePattern = /scroll|overscroll|drawer|sidebar|sheet|dialog|modal|menu|nav|radix/i;

              const markScrollSafeAreas = (viewportHeight) => {
                const candidates = [
                  document.scrollingElement,
                  document.documentElement,
                  document.body,
                  document.getElementById('root'),
                  ...document.querySelectorAll('body *')
                ].filter(Boolean);

                candidates.forEach((element) => {
                  const computed = window.getComputedStyle(element);
                  const rect = element.getBoundingClientRect();
                  const className = typeof element.className === 'string' ? element.className : '';
                  const id = element.id || '';
                  const overflowY = computed.overflowY || computed.overflow;
                  const canScroll = element.scrollHeight > element.clientHeight + 12;
                  const scrollStyled = overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay';
                  const viewportSized = rect.height >= viewportHeight * 0.45 || element === document.scrollingElement;
                  const importantSurface =
                    element === document.scrollingElement ||
                    element === document.body ||
                    element.id === 'root' ||
                    computed.position === 'fixed' ||
                    scrollCandidatePattern.test(className + ' ' + id);
                  const shouldMark = reservedBottom > 0 && canScroll && (scrollStyled || viewportSized || importantSurface);

                  if (shouldMark) {
                    if (!element.hasAttribute('data-maidroid-scroll-safe-area')) {
                      element.style.setProperty(
                        '--maidroid-original-padding-bottom',
                        computed.paddingBottom || '0px'
                      );
                    }
                    element.setAttribute('data-maidroid-scroll-safe-area', 'true');
                  } else if (element.hasAttribute('data-maidroid-scroll-safe-area')) {
                    element.removeAttribute('data-maidroid-scroll-safe-area');
                    element.style.removeProperty('--maidroid-original-padding-bottom');
                  }
                });
              };

              const apply = () => {
                const viewportHeight = readViewportHeight();
                document.documentElement.style.setProperty('--maidroid-webview-height', viewportHeight + 'px');
                document.documentElement.style.setProperty('--maidroid-webview-reserved-bottom', reservedBottom + 'px');
                ensureStyle();
                markScrollSafeAreas(viewportHeight);
                return viewportHeight;
              };

              if (!window.__maidroidViewportPatchInstalled) {
                window.__maidroidViewportPatchInstalled = true;
                window.addEventListener('resize', apply, { passive: true });
                window.addEventListener('orientationchange', () => setTimeout(apply, 50), { passive: true });
                if (window.visualViewport) {
                  window.visualViewport.addEventListener('resize', apply, { passive: true });
                  window.visualViewport.addEventListener('scroll', apply, { passive: true });
                }
                window.__maidroidViewportPatchObserver = new MutationObserver(() => {
                  window.clearTimeout(window.__maidroidViewportPatchTimer);
                  window.__maidroidViewportPatchTimer = window.setTimeout(apply, 50);
                });
                if (document.body) {
                  window.__maidroidViewportPatchObserver.observe(document.body, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class', 'style', 'open', 'data-state']
                  });
                }
              }

              const probe = document.createElement('div');
              probe.style.cssText = 'position:absolute;left:-9999px;top:-9999px;width:1px;height:100vh;pointer-events:none';
              document.body.appendChild(probe);
              const measuredVh = probe.getBoundingClientRect().height;
              probe.remove();

              const viewportHeight = apply();
              window.dispatchEvent(new Event('resize'));
              return JSON.stringify({
                patched: true,
                vh: measuredVh,
                viewportHeight,
                reservedBottom
              });
            })()
        """.trimIndent()
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

    /** 在页面第一个脚本之前注入主题和视口单位修补脚本，保证它们先于页面自己的脚本和首次渲染执行。 */
    internal fun injectThemeBootstrap(html: String): String {
        val bootstrap = themeBootstrapScript + "\n" + viewportUnitFixScript

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
        request?.let { MaiBotWebUiSupport.interceptAndInjectTheme(it, logTag) }

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
