package org.maiwithu.maidroid.pet

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import org.json.JSONObject
import org.maiwithu.maidroid.BuildConfig
import java.io.ByteArrayInputStream

/** WebView 在悬浮桌宠架构中承担的三类隔离角色。 */
internal enum class PetWebRole(val tagName: String) {
    /** 同源后台传输与 WebUI 登录页，独占服务端 Cookie 与 WS 通道，不接触本地界面。 */
    Auth("auth"),

    /** 加载桌宠形象与交互界面的透明本地页，仅通过顶级文档 MessagePort 与原生通信。 */
    Pet("pet"),

    /** 探索记录全屏页，复用本地静态资产。 */
    Records("records")
}

/**
 * 隔离沙箱化的系统 WebView 容器：
 * - 本地桌宠页面固定运行在 `https://appassets.androidplatform.net` 且施加严格 CSP；
 * - 只有完成校验的顶级文档才会获颁一次性 `WebMessagePort`，不暴露全域 `@JavascriptInterface`；
 * - 任一外部外链点击均交由外部浏览器处理，防止将原生通道或本地特权带入外部站点。
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class PetWebView(
    context: Context,
    val role: PetWebRole,
    private val assetResolver: PetAssetResolver,
    private val readAuthBootstrapScript: () -> String,
    private val isTrustedAuthUrl: (String?) -> Boolean,
    private val onAssetSourceResolved: (PetAssetSource) -> Unit,
    private val onOpenExternal: (String) -> Unit,
    private val onMainFrameError: (String) -> Unit,
    private val onMessage: (view: PetWebView, command: String, payload: JSONObject?, reply: (result: Any?, error: String?) -> Unit) -> Unit,
    private val onRenderGone: (PetWebView) -> Unit
) : WebView(context) {

    private companion object {
        const val LOG_TAG = "MaiDroidPetWeb"
        const val PET_CSP =
            "default-src 'none'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob: https: http:; font-src 'self'; media-src 'self'; " +
                "connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var messagePort: WebMessagePort? = null
    private var documentRevision = 0
    private var destroyed = false

    init {
        setBackgroundColor(Color.TRANSPARENT)
        overScrollMode = OVER_SCROLL_NEVER
        with(settings) {
            javaScriptEnabled = true
            mediaPlaybackRequiresUserGesture = role != PetWebRole.Pet
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            textZoom = 100
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)

        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    Log.w(LOG_TAG, "${role.tagName}: ${consoleMessage.message()}")
                } else if (BuildConfig.DEBUG) {
                    Log.d(LOG_TAG, "${role.tagName}: ${consoleMessage.message()}")
                }
                return true
            }
        }

        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (role == PetWebRole.Auth) return null
                val rawUrl = request.url.toString()
                if (DesktopPetProtocol.normalizeOrigin(rawUrl) != DesktopPetProtocol.LOCAL_ORIGIN) {
                    return blockedResponse()
                }
                val asset = assetResolver.resolve(request.url.path) ?: return blockedResponse()
                if (asset.source != PetAssetSource.AndroidBridge && request.url.path == "/pet/index.html") {
                    mainHandler.post { onAssetSourceResolved(asset.source) }
                }
                val rangeHeader = request.requestHeaders.entries
                    .firstOrNull { it.key.equals("Range", ignoreCase = true) }
                    ?.value
                return when (val rangeResult = parseHttpByteRange(rangeHeader, asset.bytes.size)) {
                    PetRangeResult.Unsatisfiable -> {
                        val headers = mapOf(
                            "Cache-Control" to "no-store",
                            "Accept-Ranges" to "bytes",
                            "Content-Range" to "bytes */${asset.bytes.size}",
                            "Content-Length" to "0"
                        )
                        WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            416,
                            "Range Not Satisfiable",
                            headers,
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }

                    is PetRangeResult.Partial -> {
                        val range = rangeResult.range
                        val headers = buildMap {
                            put("Cache-Control", "no-store")
                            put("Accept-Ranges", "bytes")
                            put(
                                "Content-Range",
                                "bytes ${range.start}-${range.endInclusive}/${asset.bytes.size}"
                            )
                            put("Content-Length", range.length.toString())
                            if (asset.injectCsp) {
                                put("Content-Security-Policy", PET_CSP)
                            }
                        }
                        WebResourceResponse(
                            asset.mimeType,
                            asset.encoding,
                            206,
                            "Partial Content",
                            headers,
                            ByteArrayInputStream(asset.bytes, range.start, range.length)
                        )
                    }

                    PetRangeResult.NoRange -> {
                        val headers = buildMap {
                            put("Cache-Control", "no-store")
                            put("Accept-Ranges", "bytes")
                            put("Content-Length", asset.bytes.size.toString())
                            if (asset.injectCsp) {
                                put("Content-Security-Policy", PET_CSP)
                            }
                        }
                        WebResourceResponse(
                            asset.mimeType,
                            asset.encoding,
                            200,
                            "OK",
                            headers,
                            ByteArrayInputStream(asset.bytes)
                        )
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val targetUrl = request.url.toString()
                if (role == PetWebRole.Auth && isTrustedAuthUrl(targetUrl)) {
                    return false
                }
                // 外链只由系统浏览器接管，不允许当前特权沙箱离开同源边界。
                if (request.isForMainFrame && request.hasGesture() &&
                    DesktopPetProtocol.isAllowedExternalHttpUrl(targetUrl)
                ) {
                    onOpenExternal(targetUrl)
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                documentRevision += 1
                closeMessagePort()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val safeUrl = url ?: return
                val validDocument = if (role == PetWebRole.Auth) {
                    isTrustedAuthUrl(safeUrl)
                } else {
                    DesktopPetProtocol.normalizeOrigin(safeUrl) == DesktopPetProtocol.LOCAL_ORIGIN
                }
                if (!validDocument) return

                val revision = documentRevision
                val bootstrap = if (role == PetWebRole.Auth) readAuthBootstrapScript() else "void 0"
                evaluateJavascript(bootstrap) {
                    if (destroyed || revision != documentRevision || messagePort != null) return@evaluateJavascript
                    attachMessageChannel(safeUrl, revision)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                onMainFrameError("HTTPS 证书校验失败")
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    onMainFrameError("页面加载失败：${error.description}")
                }
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail
            ): Boolean {
                onRenderGone(this@PetWebView)
                return true
            }
        }
    }

    // 通过 WebMessageChannel 建立双向端口，仅向当前已核验 origin 的顶级文档投递端口句柄。
    private fun attachMessageChannel(pageUrl: String, revision: Int) {
        val origin = DesktopPetProtocol.normalizeOrigin(pageUrl)
        if (origin.isEmpty()) return
        val ports = createWebMessageChannel()
        val nativePort = ports[0]
        messagePort = nativePort
        nativePort.setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
            override fun onMessage(port: WebMessagePort, message: WebMessage) {
                if (destroyed || revision != documentRevision || messagePort !== port) return
                var callId = ""
                try {
                    val data = message.data
                    if (data == null || data.length > DesktopPetProtocol.MAX_BRIDGE_MESSAGE_CHARS) {
                        throw IllegalArgumentException("桥接消息体积超出限制")
                    }
                    val json = JSONObject(data)
                    callId = json.getString("id")
                    val replyId = callId
                    onMessage(this@PetWebView, json.getString("command"), json.optJSONObject("payload")) { result, err ->
                        mainHandler.post {
                            if (!destroyed && revision == documentRevision && messagePort === port) {
                                port.postMessage(WebMessage(buildReplyJson(replyId, result, err)))
                            }
                        }
                    }
                } catch (error: Exception) {
                    val errText = error.message ?: error.javaClass.simpleName
                    port.postMessage(WebMessage(buildReplyJson(callId, null, errText)))
                }
            }
        }, mainHandler)
        postWebMessage(WebMessage("maibot-native-channel", arrayOf(ports[1])), origin.toUri())
    }

    private fun buildReplyJson(id: String, result: Any?, error: String?): String =
        JSONObject()
            .put("id", id)
            .put("result", result ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
            .toString()

    fun evalJs(script: String) {
        if (!destroyed) {
            evaluateJavascript(script, null)
        }
    }

    fun detachFromParent() {
        (parent as? ViewGroup)?.removeView(this)
    }

    fun destroySafely() {
        if (destroyed) return
        destroyed = true
        documentRevision += 1
        closeMessagePort()
        detachFromParent()
        stopLoading()
        destroy()
    }

    private fun closeMessagePort() {
        runCatching { messagePort?.close() }
        messagePort = null
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
}
