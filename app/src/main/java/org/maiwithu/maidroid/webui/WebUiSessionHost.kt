package org.maiwithu.maidroid.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import org.json.JSONObject
import org.maiwithu.maidroid.terminal.TerminalLog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtension.MessageDelegate
import org.mozilla.geckoview.WebExtension.MessageSender
import org.mozilla.geckoview.WebExtension.Port
import org.mozilla.geckoview.WebExtension.PortDelegate
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

/**
 * 单个 WebUI 页面的 GeckoView 会话宿主：主页 WebUI 页签与全屏 WebUiActivity 共用。
 *
 * 统一封装 GeckoView（强制使用 TextureView 后端以支持毛玻璃模糊层合成）、GeckoSession、
 * 内置 WebExtension 原生消息端口、两指外层滚动、横屏桌面视口计算、文件上传、JS 原生弹窗、
 * 下载响应转发以及内容进程崩溃/被杀恢复回调。
 */
internal class WebUiSessionHost(
    context: Context,
    private val logTag: String,
    private val fileChooser: WebUiFileChooser,
    private val downloads: WebUiDownloads,
    private val onPageFinished: (String) -> Unit = {},
    private val onMainFrameError: (String) -> Unit = {},
    private val onPageBackgroundColor: ((Int) -> Unit)? = null,
    private val onContentProcessGone: (WebUiSessionHost) -> Unit = {}
) {
    /** 横屏桌面版布局宽度（CSS px），竖屏或未启用桌面排版时为 null。 */
    @Volatile
    var desktopLayoutWidthCss: Int? = null
        private set

    /** 当前网页会话是否还可以后退（供全屏页面拦截系统返回键使用）。 */
    @Volatile
    var canGoBack: Boolean = false
        private set

    private val hostContext = context
    private val mainHandler = Handler(Looper.getMainLooper())
    private val runtime = WebUiRuntime.getOrCreate(context)
    private val session = GeckoSession(
        GeckoSessionSettings().apply {
            allowJavascript = true
            viewportMode = GeckoSessionSettings.VIEWPORT_MODE_MOBILE
            useTrackingProtection = false
            suspendMediaWhenInactive = true
        }
    )

    private var bridgeExtension: WebExtension? = null
    private var bridgePort: Port? = null
    private var bridgeReady = false
    private var pendingLoadUrl: String? = null
    private var currentPageUrl = ""
    private var mainFrameFailed = false
    private var processGoneReported = false
    private var released = false
    private var sessionActive = true

    private var desktopViewportEnabled = false
    private var layoutBasisWidthPx = 0
    private var configureEverSent = false
    private var activeDialog: AlertDialog? = null

    private val twoFingerScroll: WebUiTwoFingerScroll

    /** 供宿主嵌入 Compose AndroidView 或 Activity FrameLayout 的 GeckoView 视图实例。 */
    @SuppressLint("ClickableViewAccessibility")
    val view: GeckoView = GeckoView(context).apply {
        // 竖屏灵动胶囊与横屏侧栏通过 AndroidLiquidGlass 的 LayerBackdrop 实时采样网页画面做液态玻璃折射：
        // SurfaceView 会独立穿透宿主图层导致模糊变黑/穿洞，必须使用 TextureView 后端。
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        coverUntilFirstPaint(Color.BLACK)
        setBackgroundColor(Color.BLACK)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private val messageDelegate = object : MessageDelegate {
        override fun onConnect(port: Port) {
            runOnMain {
                if (released) {
                    runCatching { port.disconnect() }
                    return@runOnMain
                }
                bridgePort = port
                port.setDelegate(object : PortDelegate {
                    override fun onPortMessage(message: Any, targetPort: Port) {
                        handlePortMessage(message)
                    }

                    override fun onDisconnect(targetPort: Port) {
                        runOnMain {
                            // 仅在断开的就是当前活跃端口时清零，避免旧页面延迟断开误清了新页面的端口。
                            if (bridgePort === targetPort) {
                                bridgePort = null
                            }
                        }
                    }
                })
                // 扩展一连上就推最新桌面/竖屏视口配置，让 content.js 立刻同步页面布局与背景上报。
                sendConfigure()
            }
        }

        override fun onMessage(
            nativeApp: String,
            message: Any,
            sender: MessageSender
        ): GeckoResult<Any>? {
            if (released || nativeApp != WebUiBridgeProtocol.NATIVE_APP_NAME) return null
            val rawJson = (message as? JSONObject)?.toString() ?: message.toString()
            return when (val event = WebUiBridgeProtocol.parseEvent(rawJson)) {
                is WebUiBridgeEvent.BlobBegin ->
                    downloads.handleBlobBegin(event.fileName, event.mimeType, event.sizeBytes)
                is WebUiBridgeEvent.BlobAppend ->
                    downloads.handleBlobAppend(event.id, event.base64)
                is WebUiBridgeEvent.BlobFinish ->
                    downloads.handleBlobFinish(event.id)
                is WebUiBridgeEvent.BlobFail ->
                    downloads.handleBlobFail(event.id, event.message)
                else -> null
            }
        }
    }

    init {
        twoFingerScroll = WebUiTwoFingerScroll(
            targetView = view,
            onBegin = { x, y, widthPx ->
                sendPortMessage(WebUiBridgeProtocol.twoFingerBeginJsonText(x, y, widthPx))
            },
            onMove = { dy, generation ->
                sendPortMessage(WebUiBridgeProtocol.twoFingerMoveJsonText(dy, generation))
            }
        )
        view.setOnTouchListener(twoFingerScroll)

        // 监听视图宽度变化（如横屏首次排版后才让出摄像头挖孔安全区），按新宽度原地更新桌面视口。
        view.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) {
                onViewWidthChanged()
            }
        }

        installSessionDelegates()
        session.open(runtime)
        view.setSession(session)

        // 确保内置扩展安装完成后先挂好会话级 MessageDelegate，再放行挂起的首次 loadUri，
        // 避免页面 document_start 过早执行 connectNative("maidroid") 时宿主还没监听。
        WebUiRuntime.ensureBridgeExtension(context).accept(
            { ext ->
                runOnMain {
                    if (released) return@runOnMain
                    bridgeExtension = ext
                    if (ext != null) {
                        session.webExtensionController.setMessageDelegate(
                            ext,
                            messageDelegate,
                            WebUiBridgeProtocol.NATIVE_APP_NAME
                        )
                    }
                    bridgeReady = true
                    flushPendingLoad()
                }
            },
            { error ->
                Log.w(logTag, "Bridge extension unavailable, loading without bridge: ${error?.message}")
                runOnMain {
                    if (released) return@runOnMain
                    bridgeReady = true
                    flushPendingLoad()
                }
            }
        )
    }

    /** 按当前视图尺寸启用或关闭横屏桌面版布局宽度，并将最新视口配置下发给内置扩展。 */
    fun applyDesktopViewport(enabled: Boolean = true) {
        if (released) return
        desktopViewportEnabled = enabled
        val layoutWidth = if (enabled) {
            WebUiDesktopViewport.layoutWidthCss(
                widthPx = view.width,
                heightPx = view.height,
                density = view.resources.displayMetrics.density
            )
        } else {
            null
        }
        val changed = layoutWidth != desktopLayoutWidthCss || !configureEverSent
        desktopLayoutWidthCss = layoutWidth
        layoutBasisWidthPx = view.width
        if (changed) {
            configureEverSent = true
            sendConfigure()
        }
    }

    /** 加载目标地址；若内置扩展仍在完成安装，则记下最新地址待扩展就位后立即发起加载。 */
    fun loadUrl(url: String) {
        if (released) return
        currentPageUrl = url
        if (!bridgeReady) {
            pendingLoadUrl = url
            return
        }
        pendingLoadUrl = null
        session.loadUri(url)
    }

    /** 若网页历史栈允许后退，则执行后退。 */
    fun goBack() {
        if (!released && canGoBack) {
            session.goBack()
        }
    }

    /** 按前后台与当前页签可见状态冻结或恢复 GeckoSession 渲染和 JS 定时器，减少不可见耗时耗电。 */
    fun setActive(active: Boolean) {
        if (released || sessionActive == active) return
        sessionActive = active
        if (session.isOpen) {
            runCatching { session.setActive(active) }
        }
    }

    /**
     * 释放 GeckoView 与 GeckoSession 资源（幂等）：
     * 停掉两指惯性 -> 关闭遗留系统弹窗 -> 断开扩展端口与消息代理 -> 摘除视图并解绑/关闭会话。
     */
    fun release() {
        if (released) return
        released = true
        pendingLoadUrl = null

        twoFingerScroll.release()
        view.setOnTouchListener(null)

        activeDialog?.cancel()
        activeDialog = null

        runCatching { bridgePort?.disconnect() }
        bridgePort = null
        bridgeExtension?.let { ext ->
            runCatching {
                session.webExtensionController.setMessageDelegate(
                    ext,
                    null,
                    WebUiBridgeProtocol.NATIVE_APP_NAME
                )
            }
        }
        bridgeExtension = null

        (view.parent as? ViewGroup)?.removeView(view)
        runCatching { view.releaseSession() }

        session.contentDelegate = null
        session.navigationDelegate = null
        session.progressDelegate = null
        session.promptDelegate = null
        if (session.isOpen) {
            runCatching { session.close() }
        }
    }

    private fun installSessionDelegates() {
        session.navigationDelegate = object : NavigationDelegate {
            override fun onCanGoBack(targetSession: GeckoSession, canGoBack: Boolean) {
                this@WebUiSessionHost.canGoBack = canGoBack
            }

            override fun onLocationChange(
                targetSession: GeckoSession,
                url: String?,
                perms: List<ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!url.isNullOrBlank()) {
                    currentPageUrl = url
                }
            }

            override fun onLoadRequest(
                targetSession: GeckoSession,
                request: LoadRequest
            ): GeckoResult<AllowOrDeny> {
                // 拦截新窗口打开请求（如 target="_blank"），改为在当前会话原地打开，避免多窗口无响应。
                if (request.target == NavigationDelegate.TARGET_WINDOW_NEW) {
                    if (!released && request.uri.isNotBlank()) {
                        mainHandler.post {
                            if (!released) targetSession.loadUri(request.uri)
                        }
                    }
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLoadError(
                targetSession: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                mainFrameFailed = true
                val errorText = "${error.code}: ${describeWebError(error)}"
                runOnMain {
                    if (!released) {
                        onMainFrameError(errorText)
                    }
                }
                return GeckoResult.fromValue(null)
            }
        }

        session.progressDelegate = object : ProgressDelegate {
            override fun onPageStart(targetSession: GeckoSession, url: String) {
                mainFrameFailed = false
                if (url.isNotBlank()) {
                    currentPageUrl = url
                }
            }

            override fun onPageStop(targetSession: GeckoSession, success: Boolean) {
                if (released || !success || mainFrameFailed) return
                // 页面停稳后再同步一次视口配置，保证慢加载或重定向页面的首帧配置不被漏掉。
                sendConfigure()
                onPageFinished(currentPageUrl)
            }
        }

        session.contentDelegate = object : ContentDelegate {
            override fun onExternalResponse(targetSession: GeckoSession, response: WebResponse) {
                if (!released) {
                    downloads.onExternalResponse(response)
                }
            }

            override fun onCrash(targetSession: GeckoSession) {
                handleContentProcessGone(crashed = true)
            }

            override fun onKill(targetSession: GeckoSession) {
                handleContentProcessGone(crashed = false)
            }
        }

        session.promptDelegate = object : PromptDelegate {
            override fun onFilePrompt(
                targetSession: GeckoSession,
                prompt: FilePrompt
            ): GeckoResult<PromptResponse> {
                if (released) {
                    return if (prompt.isComplete) GeckoResult() else GeckoResult.fromValue(prompt.dismiss())
                }
                return fileChooser.show(prompt, hostContext)
            }

            override fun onAlertPrompt(
                targetSession: GeckoSession,
                prompt: AlertPrompt
            ): GeckoResult<PromptResponse> = showAlertPrompt(prompt)

            override fun onButtonPrompt(
                targetSession: GeckoSession,
                prompt: ButtonPrompt
            ): GeckoResult<PromptResponse> = showButtonPrompt(prompt)

            override fun onTextPrompt(
                targetSession: GeckoSession,
                prompt: TextPrompt
            ): GeckoResult<PromptResponse> = showTextPrompt(prompt)
        }
    }

    private fun handlePortMessage(message: Any) {
        val rawJson = (message as? JSONObject)?.toString() ?: message.toString()
        when (val event = WebUiBridgeProtocol.parseEvent(rawJson)) {
            is WebUiBridgeEvent.PageBackground -> runOnMain {
                if (!released) onPageBackgroundColor?.invoke(event.argb)
            }
            is WebUiBridgeEvent.TwoFingerResult -> runOnMain {
                if (!released) twoFingerScroll.onFlingMoveResult(event.canContinue, event.generation)
            }
            else -> Unit
        }
    }

    private fun sendConfigure() {
        val payload = WebUiBridgeProtocol.configureJsonText(
            desktopWidthCss = desktopLayoutWidthCss,
            enableViewportPatch = desktopLayoutWidthCss == null
        )
        sendPortMessage(payload)
    }

    private fun sendPortMessage(jsonText: String) {
        if (released) return
        val port = bridgePort ?: return
        runCatching { port.postMessage(JSONObject(jsonText)) }
    }

    private fun flushPendingLoad() {
        val target = pendingLoadUrl ?: return
        pendingLoadUrl = null
        session.loadUri(target)
    }

    private fun onViewWidthChanged() {
        if (released || !desktopViewportEnabled || view.width == layoutBasisWidthPx) return
        val updated = WebUiDesktopViewport.layoutWidthCss(
            widthPx = view.width,
            heightPx = view.height,
            density = view.resources.displayMetrics.density
        )
        layoutBasisWidthPx = view.width
        if (updated == desktopLayoutWidthCss) return

        desktopLayoutWidthCss = updated
        sendConfigure()
        Log.d(logTag, "desktop viewport resized to width=$updated at ${view.width}x${view.height}")
    }

    private fun handleContentProcessGone(crashed: Boolean) {
        runOnMain {
            if (released || processGoneReported) return@runOnMain
            processGoneReported = true
            val reason = if (crashed) "崩溃了" else "被系统回收了（内存不足）"
            Log.w(logTag, "GeckoView content process gone, crashed=$crashed")
            TerminalLog.append("[MaiDroid] 网页的渲染进程$reason，MaiDroid 换一个新的网页窗口重新打开")
            onContentProcessGone(this@WebUiSessionHost)
        }
    }

    private fun showAlertPrompt(prompt: AlertPrompt): GeckoResult<PromptResponse> =
        showPromptDialog(prompt, prompt.message) { builder, complete ->
            builder.setPositiveButton("确定") { _, _ -> complete { prompt.dismiss() } }
        }

    private fun showButtonPrompt(prompt: ButtonPrompt): GeckoResult<PromptResponse> =
        showPromptDialog(prompt, prompt.message) { builder, complete ->
            builder.setPositiveButton("确定") { _, _ -> complete { prompt.confirm(ButtonPrompt.Type.POSITIVE) } }
            builder.setNegativeButton("取消") { _, _ -> complete { prompt.confirm(ButtonPrompt.Type.NEGATIVE) } }
        }

    private fun showTextPrompt(prompt: TextPrompt): GeckoResult<PromptResponse> =
        showPromptDialog(prompt, prompt.message) { builder, complete ->
            val dialogContext = builder.context
            val input = EditText(dialogContext).apply {
                setText(prompt.defaultValue.orEmpty())
                setSelection(text?.length ?: 0)
            }
            val horizontalMargin = (DIALOG_INPUT_MARGIN_DP * dialogContext.resources.displayMetrics.density).toInt()
            val container = FrameLayout(dialogContext).apply {
                setPadding(horizontalMargin, horizontalMargin / 2, horizontalMargin, 0)
                addView(input)
            }
            builder.setView(container)
            builder.setPositiveButton("确定") { _, _ -> complete { prompt.confirm(input.text?.toString().orEmpty()) } }
            builder.setNegativeButton("取消") { _, _ -> complete { prompt.dismiss() } }
        }

    private fun showPromptDialog(
        prompt: BasePrompt,
        message: String?,
        configure: (AlertDialog.Builder, (() -> PromptResponse) -> Unit) -> Unit
    ): GeckoResult<PromptResponse> {
        if (prompt.isComplete) return GeckoResult()
        val activity = usableActivity() ?: return GeckoResult.fromValue(prompt.dismiss())
        val result = GeckoResult<PromptResponse>()
        var dialog: AlertDialog? = null
        val guard = PromptSettlementGuard<PromptResponse>(
            isAlreadyComplete = { prompt.isComplete }
        ) { response ->
            if (activeDialog === dialog) {
                activeDialog = null
            }
            result.complete(response)
        }
        val builder = AlertDialog.Builder(activity).apply {
            prompt.title?.takeIf { it.isNotBlank() }?.let(::setTitle)
            message?.takeIf { it.isNotBlank() }?.let(::setMessage)
        }
        configure(builder) { produce -> guard.settleOnce(produce) }
        // 统一监听 dismiss，用惰性闭包确保已通过按钮完成弹窗时不会在 Dialog 关闭阶段重复调用 prompt.dismiss()。
        builder.setOnDismissListener {
            if (activeDialog === dialog) {
                activeDialog = null
            }
            guard.settleOnce { prompt.dismiss() }
        }
        val created = builder.create()
        dialog = created
        // 网页主动撤销或关闭标签页时同步关掉原生弹窗，防止界面残留。
        prompt.delegate = object : PromptInstanceDelegate {
            override fun onPromptDismiss(targetPrompt: BasePrompt) {
                runOnMain { created.dismiss() }
            }
        }
        activeDialog?.dismiss()
        activeDialog = created
        runCatching { created.show() }.onFailure { error ->
            Log.w(logTag, "Failed to show prompt dialog: ${error.message}")
            if (activeDialog === created) {
                activeDialog = null
            }
            guard.settleOnce { prompt.dismiss() }
        }
        return result
    }

    private fun usableActivity(): Activity? {
        if (released) return null
        val activity = findActivity(hostContext) ?: return null
        return activity.takeUnless { it.isFinishing || it.isDestroyed }
    }

    private fun findActivity(context: Context): Activity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun describeWebError(error: WebRequestError): String = when (error.code) {
        WebRequestError.ERROR_CONNECTION_REFUSED -> "连接被拒绝"
        WebRequestError.ERROR_NET_TIMEOUT -> "连接超时"
        WebRequestError.ERROR_NET_RESET -> "连接被重置"
        WebRequestError.ERROR_NET_INTERRUPT -> "网络中断"
        WebRequestError.ERROR_OFFLINE -> "网络离线"
        WebRequestError.ERROR_UNKNOWN_HOST -> "域名无法解析"
        WebRequestError.ERROR_PORT_BLOCKED -> "端口被禁止"
        WebRequestError.ERROR_REDIRECT_LOOP -> "重定向过多"
        WebRequestError.ERROR_PROXY_CONNECTION_REFUSED,
        WebRequestError.ERROR_UNKNOWN_PROXY_HOST -> "代理连接失败"
        WebRequestError.ERROR_SECURITY_SSL,
        WebRequestError.ERROR_SECURITY_BAD_CERT -> "安全连接失败"
        WebRequestError.ERROR_FILE_NOT_FOUND -> "文件不存在"
        WebRequestError.ERROR_MALFORMED_URI -> "地址格式无效"
        else -> "页面加载错误"
    }

    private companion object {
        const val DIALOG_INPUT_MARGIN_DP = 20
    }
}

/**
 * 弹窗一次性结算保护器：将按钮结果生成推迟到真正尚未结算时才求值，
 * 防止点击确认/取消后系统继续派发 Dialog.onDismiss 时再次调用 GeckoView 的 prompt.dismiss() 引发二次结算崩溃。
 */
internal class PromptSettlementGuard<R>(
    private val isAlreadyComplete: () -> Boolean = { false },
    private val onComplete: (R) -> Unit
) {
    private var settled = false

    fun settleOnce(produceResponse: () -> R): Boolean {
        if (settled || isAlreadyComplete()) {
            settled = true
            return false
        }
        settled = true
        onComplete(produceResponse())
        return true
    }
}
