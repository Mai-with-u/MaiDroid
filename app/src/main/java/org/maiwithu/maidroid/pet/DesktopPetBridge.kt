package org.maiwithu.maidroid.pet

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 隔离管理三个桌宠 WebView（auth / pet / records）之间的双向 JSON 桥接协议与超时表。
 * 保持通信细节与超时映射集中在一处，让总控管理器只专注状态流转与生命周期编排。
 */
internal class DesktopPetBridge(
    private val appContext: Context,
    private val serverBaseUrl: String,
    private val assetResolver: PetAssetResolver,
    private val callbacks: Callbacks
) {
    internal interface Callbacks {
        fun isConnected(): Boolean
        fun onAssetSourceResolved(source: PetAssetSource)
        fun onMainFrameError(message: String)
        fun onRenderGone(role: PetWebRole)
        fun onOpenExternal(url: String)
        fun onTransportEvent(payload: JSONObject)
        fun onPetReady()
        fun onPetFit(cssHeight: Double)
        fun onPetRegion(rects: JSONArray)
        fun onPetDragBegin()
        fun onPetDragMove()
        fun onPetDragEnd()
        fun onFocusChanged(editing: Boolean)
        fun onOpenExplorationRecords()
        fun onCloseRecords()
        fun onCopyText(text: String)
        fun onExitRequested()
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 60_000L
        const val PAGE_COMMAND_TIMEOUT_MS = 15_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRequests = HashMap<String, (result: Any?, error: String?) -> Unit>()
    private val pageCommands = HashMap<String, PetWebRole>()

    var authView: PetWebView? = null
    var petView: PetWebView? = null
    var recordsView: PetWebView? = null
    var nonce: String = generateHexId()
        private set

    fun createWebView(context: Context, role: PetWebRole): PetWebView =
        PetWebView(
            context = context,
            role = role,
            assetResolver = assetResolver,
            readAuthBootstrapScript = ::buildAuthBootstrapScript,
            isTrustedAuthUrl = { url -> DesktopPetProtocol.isTrustedServerUrl(serverBaseUrl, url) },
            onAssetSourceResolved = callbacks::onAssetSourceResolved,
            onOpenExternal = callbacks::onOpenExternal,
            onMainFrameError = callbacks::onMainFrameError,
            onMessage = ::handleWebMessage,
            onRenderGone = { deadView -> callbacks.onRenderGone(deadView.role) }
        )

    /** 断开传输脚本、推进新 nonce 并清理全部未决回调与三个 WebView。 */
    fun resetAndDestroyAll() {
        evalTransport("stop", JSONArray())
        nonce = generateHexId()
        pendingRequests.values.forEach { reply -> reply(null, "悬浮桌宠连接已关闭") }
        pendingRequests.clear()
        pageCommands.clear()

        petView?.destroySafely()
        petView = null
        recordsView?.destroySafely()
        recordsView = null
        authView?.destroySafely()
        authView = null
    }

    fun clearRecordsIfMatches(view: PetWebView?) {
        if (view != null && recordsView === view) {
            recordsView = null
            view.destroySafely()
        }
    }

    fun checkInputEditing(onNotEditing: () -> Unit) {
        val pet = petView ?: return
        pet.evaluateJavascript("document.activeElement.matches('input,textarea,[contenteditable=true]')") { editing ->
            if ("false" == editing) {
                onNotEditing()
            }
        }
    }

    fun evalTransport(method: String, args: JSONArray) {
        val view = authView ?: return
        val quotedNonce = JSONObject.quote(nonce)
        view.evalJs(
            "if(window.__petTransport&&window.__petTransport.nonce===$quotedNonce)" +
                "window.__petTransport.$method(...$args);"
        )
    }

    fun dispatchPageCommand(
        target: PetWebView,
        id: String,
        command: JSONObject,
        ack: Boolean
    ) {
        if (ack) {
            pageCommands[id] = target.role
            val activeNonce = nonce
            mainHandler.postDelayed({
                if (nonce == activeNonce && pageCommands.remove(id) != null) {
                    evalTransport(
                        "commandResult",
                        JSONArray().put(id).put(JSONObject.NULL).put("桌宠页面响应超时")
                    )
                }
            }, PAGE_COMMAND_TIMEOUT_MS)
        }
        val envelope = JSONObject().put("id", id).put("command", command).toString()
        target.evalJs("window.__desktopPetDispatch($envelope);")
    }

    fun generateHexId(): String = UUID.randomUUID().toString().replace("-", "")

    private fun buildAuthBootstrapScript(): String {
        val polyfill = readPetAndroidAsset("polyfill.js")
        val channel = readPetAndroidAsset("channel.js")
        val transport = readPetAndroidAsset("transport.js")
        val configJson = JSONObject()
            .put("server", serverBaseUrl)
            .put("nonce", nonce)
            .toString()
        return "$polyfill\n$channel\nwindow.__petTransportConfig=$configJson;\n$transport"
    }

    private fun readPetAndroidAsset(name: String): String =
        appContext.assets.open("pet-android/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun handleWebMessage(
        source: PetWebView,
        command: String,
        payload: JSONObject?,
        reply: (result: Any?, error: String?) -> Unit
    ) {
        if (payload == null) {
            reply(null, "消息格式无效")
            return
        }
        if (source.role == PetWebRole.Auth) {
            if (source !== authView ||
                !DesktopPetProtocol.isTrustedServerUrl(serverBaseUrl, source.url) ||
                command != "transport_message" ||
                nonce != payload.optString("nonce")
            ) {
                reply(null, "当前页面无权访问传输桥接")
                return
            }
            runCatching { callbacks.onTransportEvent(payload.getJSONObject("payload")) }
                .onSuccess { reply(null, null) }
                .onFailure { err -> reply(null, err.message ?: "处理传输消息失败") }
            return
        }

        if (source !== petView && source !== recordsView) {
            reply(null, "窗口已失效")
            return
        }

        runCatching {
            when (command) {
                "request" -> {
                    if (!callbacks.isConnected()) throw IllegalStateException("桌宠未连接，请等待重连")
                    val id = generateHexId()
                    pendingRequests[id] = reply
                    mainHandler.postDelayed({
                        pendingRequests.remove(id)?.invoke(null, "请求超时，请刷新确认结果")
                    }, REQUEST_TIMEOUT_MS)
                    evalTransport(
                        "request",
                        JSONArray()
                            .put(id)
                            .put(payload.getString("method"))
                            .put(payload.getString("path"))
                            .put(payload.opt("payload") ?: JSONObject.NULL)
                    )
                    return
                }

                "event" -> evalTransport("sendEvent", JSONArray().put(payload))
                "page_result" -> {
                    val id = payload.getString("id")
                    if (source.role == pageCommands.remove(id)) {
                        evalTransport(
                            "commandResult",
                            JSONArray()
                                .put(id)
                                .put(payload.opt("result") ?: JSONObject.NULL)
                                .put(payload.opt("error") ?: JSONObject.NULL)
                        )
                    }
                }

                "page_open_external" -> callbacks.onOpenExternal(payload.getString("value"))
                "focus" -> if (source === petView) callbacks.onFocusChanged(payload.optBoolean("editing"))
                "pet_call" -> {
                    val result = handlePetCall(source, payload.getString("method"), payload.getJSONArray("args"))
                    reply(result, null)
                    return
                }

                else -> throw SecurityException("不支持的桌宠原生指令：$command")
            }
            reply(null, null)
        }.onFailure { err ->
            reply(null, err.message ?: err.javaClass.simpleName)
        }
    }

    private fun handlePetCall(source: PetWebView, method: String, args: JSONArray): Any? {
        if (source === recordsView) {
            return when (method) {
                "refresh" -> {
                    evalTransport(
                        "sendEvent",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "exploration_tasks")
                                .put("task_id", args.opt(0))
                                .put("request_id", "records:" + args.get(1))
                        )
                    )
                    null
                }

                "window_control" -> {
                    if (args.optString(0) == "close") callbacks.onCloseRecords()
                    false
                }

                "window_gesture", "window_gesture_end" -> null
                else -> throw SecurityException("探索记录页无权调用：$method")
            }
        }

        when (method) {
            "ready" -> {
                callbacks.onPetReady()
                return true
            }

            "fit" -> callbacks.onPetFit(args.getDouble(1))
            "region" -> callbacks.onPetRegion(args.getJSONArray(0))
            "drag_begin" -> callbacks.onPetDragBegin()
            "drag_move" -> callbacks.onPetDragMove()
            "drag_end" -> callbacks.onPetDragEnd()
            "open_exploration_records" -> callbacks.onOpenExplorationRecords()
            "copy_panel_content" -> {
                if (args.getString(0) != "text") {
                    throw IllegalArgumentException("Android 版暂仅支持复制文字")
                }
                callbacks.onCopyText(args.getString(1))
            }

            "exit_client" -> mainHandler.post { callbacks.onExitRequested() }
            else -> throw IllegalArgumentException("Android 版不支持操作：$method")
        }
        return null
    }

    fun resolvePendingRequest(id: String, data: Any?, error: String?) {
        pendingRequests.remove(id)?.invoke(data, error)
    }
}
