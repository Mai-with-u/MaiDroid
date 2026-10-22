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

    // 只匹配样式声明里的 env(safe-area-inset-bottom)；Tailwind 类名选择器里的括号带反斜杠转义，不会被误匹配。
    private val bottomSafeAreaRegex =
        Regex("""env\(\s*safe-area-inset-bottom\s*(?:,[^()]*)?\)""", RegexOption.IGNORE_CASE)

    // WebUI 只跑在本机回环地址上，样式表改写只针对这些主机。
    private val loopbackHosts = setOf("127.0.0.1", "localhost", "[::1]")

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
     * 兜底：WebView 主框架里 vh/dvh/svh/lvh 恒为 0 时，改写样式表让页面仍能排版。
     * 已知成因是宿主给 WebView 的布局高度是 WRAP_CONTENT（Compose 的 AndroidView 默认如此），
     * HomeWebUiTab 已显式设为铺满父视图，正常情况下这里探针量得出高度，脚本不会启用；
     * 保留它是为了应对以后换宿主布局、或别的宿主再次踩到同一个坑。
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
                  // 对话框类容器只把最大高度限制在视口减 1rem，不再减导航栏高度：
                  // 贴底的内边距已经由样式表里的安全区改写避开导航栏，这里再减一次会让对话框底部多出一段空白。
                  '[role="dialog"],[data-radix-dialog-content],[class*="DialogContent"],[class*="dialog-content"],[class*="modal-content"],[class*="drawer"],[class*="Drawer"],[class*="sidebar"],[class*="Sidebar"]{max-height:calc(var(--maidroid-webview-height) - 1rem)!important;overflow-y:auto!important;-webkit-overflow-scrolling:touch!important}',
                  '[class~="max-h-screen"]{max-height:calc(var(--maidroid-webview-height) - var(--maidroid-webview-reserved-bottom) - 1rem)!important}'
                ].join('\n');
              };

              const scrollCandidatePattern = /scroll|overscroll|drawer|sidebar|sheet|dialog|modal|menu|nav|radix/i;

              const markScrollSafeAreas = (viewportHeight) => {
                // 上游移动端底栏在场时，内容区底部留白已由上游按底栏实际位置算好（底栏已随安全区上移到导航栏之上），
                // 这里不再叠加启发式留白；底栏隐藏（聊天页等）时才由这里兜底给滚动区让出导航栏高度。
                const upstreamDockPresent = !!document.querySelector('[data-mobile-tabbar="true"]');
                const scrollPaddingActive = reservedBottom > 0 && !upstreamDockPresent;
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
                  const shouldMark = scrollPaddingActive && canScroll && (scrollStyled || viewportSized || importantSurface);

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
