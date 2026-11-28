package org.maiwithu.maidroid.webui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.maiwithu.maidroid.applyPreferredHighRefreshRate
import org.maiwithu.maidroid.requestHighFrameRateOnView
import org.maiwithu.maidroid.resolveActivity
import org.maiwithu.maidroid.terminal.TerminalLog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate.LoadRequest
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt.Choice
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
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
import java.util.concurrent.atomic.AtomicLong

/**
 * 单个 WebUI 页面的 GeckoView 会话宿主：主页 WebUI 页签与全屏 WebUiActivity 共用。
 *
 * 统一封装 GeckoView（使用 TextureView 后端以兼容 Compose AnimatedContent 与深色容器合成并请求高刷新率）、
 * GeckoSession、内置 WebExtension 原生消息端口、两指外层滚动、横屏桌面视口计算、文件上传、JS 原生弹窗、
 * 下载响应转发以及内容进程崩溃/被杀恢复回调。
 */
internal class WebUiSessionHost(
    context: Context,
    private val logTag: String,
    private val fileChooser: WebUiFileChooser,
    private val downloads: WebUiDownloads,
    initialBackgroundColor: Int,
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

    private val promptIdCounter = AtomicLong(1L)
    private val _activePrompt = MutableStateFlow<WebUiPromptRequest?>(null)

    /** 当前待用户在 Compose 液态玻璃卡片中处理的原生网页弹窗请求，未展示或已结算时为 null。 */
    val activePrompt: StateFlow<WebUiPromptRequest?> = _activePrompt.asStateFlow()

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
    private val promptController = WebUiPromptController(
        hostContext = context,
        logTag = logTag,
        isReleased = { released },
        runOnMain = { block -> runOnMain(block) },
        dismissActiveComposePrompt = {
            _activePrompt.value?.onDismiss?.invoke()
            _activePrompt.value = null
        }
    )

    private val twoFingerScroll: WebUiTwoFingerScroll

    /** 供宿主嵌入 Compose AndroidView 或 Activity FrameLayout 的 GeckoView 视图实例。 */
    @SuppressLint("ClickableViewAccessibility")
    val view: GeckoView = GeckoView(context).apply {
        // 在 Compose AnimatedContent 与深色容器内必须使用 TextureView，避免 SurfaceView 打洞被 RenderNode 遮挡造成黑屏；
        // 常规浏览通过关闭整屏 layerBackdrop 与离屏 alpha 层保障流畅度，并同步为内层 TextureView 投票最高刷新率。
        setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        val preferredHz = resolveActivity(context)?.let(::applyPreferredHighRefreshRate)
        requestHighFrameRateOnView(this, preferredHz)
        for (index in 0 until childCount) {
            requestHighFrameRateOnView(getChildAt(index), preferredHz)
        }
        // 首帧遮罩与容器底色由调用方按当前主题色板传入，避免 webui 层反向依赖 ui.theme。
        coverUntilFirstPaint(initialBackgroundColor)
        setBackgroundColor(initialBackgroundColor)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        overScrollMode = View.OVER_SCROLL_NEVER
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /** 切换浅/深色主题时同步更新 GeckoView 首帧遮罩与容器底色，防止加载过渡期闪出异色底板。 */
    fun updateThemeBackground(backgroundColor: Int) {
        view.coverUntilFirstPaint(backgroundColor)
        view.setBackgroundColor(backgroundColor)
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

        _activePrompt.value?.onDismiss?.invoke()
        _activePrompt.value = null
        promptController.release()

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
        session.permissionDelegate = null
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

            override fun onChoicePrompt(
                targetSession: GeckoSession,
                prompt: ChoicePrompt
            ): GeckoResult<PromptResponse> = showChoicePrompt(prompt)

            override fun onDateTimePrompt(
                targetSession: GeckoSession,
                prompt: DateTimePrompt
            ): GeckoResult<PromptResponse> = showDateTimePrompt(prompt)

            override fun onColorPrompt(
                targetSession: GeckoSession,
                prompt: ColorPrompt
            ): GeckoResult<PromptResponse> = showColorPrompt(prompt)
        }

        session.permissionDelegate = object : PermissionDelegate {
            override fun onContentPermissionRequest(
                targetSession: GeckoSession,
                perm: ContentPermission
            ): GeckoResult<Int> {
                val isAutoplay = perm.permission == PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    perm.permission == PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE
                val decision = if (isAutoplay && isLocalLoopbackUri(perm.uri)) {
                    ContentPermission.VALUE_ALLOW
                } else {
                    ContentPermission.VALUE_DENY
                }
                return GeckoResult.fromValue(decision)
            }
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
        showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.Alert(
                id = requestId,
                title = prompt.title.orEmpty(),
                message = prompt.message.orEmpty(),
                onDismiss = { settle { prompt.dismiss() } }
            )
        }

    private fun showButtonPrompt(prompt: ButtonPrompt): GeckoResult<PromptResponse> =
        showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.Confirm(
                id = requestId,
                title = prompt.title.orEmpty(),
                message = prompt.message.orEmpty(),
                onConfirm = { settle { prompt.confirm(ButtonPrompt.Type.POSITIVE) } },
                onCancel = { settle { prompt.confirm(ButtonPrompt.Type.NEGATIVE) } },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }

    private fun showTextPrompt(prompt: TextPrompt): GeckoResult<PromptResponse> =
        showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.TextInput(
                id = requestId,
                title = prompt.title.orEmpty(),
                message = prompt.message.orEmpty(),
                defaultValue = prompt.defaultValue.orEmpty(),
                placeholder = "",
                allowClear = false,
                onConfirm = { value -> settle { prompt.confirm(value) } },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }

    private fun showChoicePrompt(prompt: ChoicePrompt): GeckoResult<PromptResponse> {
        val rows = flattenChoiceItems(toChoiceNodes(prompt.choices))
        if (rows.none { it is WebUiChoiceRow.Option }) {
            return if (prompt.isComplete) GeckoResult() else GeckoResult.fromValue(prompt.dismiss())
        }
        val mode = when (prompt.type) {
            ChoicePrompt.Type.MULTIPLE -> WebUiChoiceMode.Multiple
            ChoicePrompt.Type.MENU -> WebUiChoiceMode.Menu
            else -> WebUiChoiceMode.Single
        }
        val initialSelectedIds = initialSelectedChoiceIds(rows)
        return showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.Choice(
                id = requestId,
                title = prompt.title.orEmpty(),
                message = prompt.message.orEmpty(),
                mode = mode,
                rows = rows,
                initialSelectedIds = initialSelectedIds,
                // 单选/菜单模式必须显式调用单字符串重载，避免 GeckoView 对非多选传数组抛出异常。
                onConfirmSingle = { optionId -> settle { prompt.confirm(optionId) } },
                onConfirmMultiple = { selectedIds -> settle { prompt.confirm(selectedIds) } },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }
    }

    private fun showDateTimePrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> {
        if (prompt.isComplete) return GeckoResult()
        if (promptController.usableActivity() == null &&
            prompt.type != DateTimePrompt.Type.MONTH &&
            prompt.type != DateTimePrompt.Type.WEEK
        ) {
            return GeckoResult.fromValue(prompt.dismiss())
        }
        return when (prompt.type) {
            DateTimePrompt.Type.DATE -> promptController.showDatePickerPrompt(prompt)
            DateTimePrompt.Type.TIME -> promptController.showTimePickerPrompt(prompt)
            DateTimePrompt.Type.DATETIME_LOCAL -> promptController.showDateTimeLocalPrompt(prompt)
            DateTimePrompt.Type.MONTH,
            DateTimePrompt.Type.WEEK -> showTextDateTimePrompt(prompt)
            else -> showTextDateTimePrompt(prompt)
        }
    }

    private fun showTextDateTimePrompt(prompt: DateTimePrompt): GeckoResult<PromptResponse> =
        showComposePrompt(prompt) { requestId, settle ->
            val fallbackTitle = when (prompt.type) {
                DateTimePrompt.Type.MONTH -> "选择月份"
                DateTimePrompt.Type.WEEK -> "选择星期"
                else -> "输入日期时间"
            }
            val placeholder = when (prompt.type) {
                DateTimePrompt.Type.MONTH -> "格式：YYYY-MM（例如 2026-11）"
                DateTimePrompt.Type.WEEK -> "格式：YYYY-Www（例如 2026-W48）"
                else -> "输入日期或时间"
            }
            WebUiPromptRequest.TextInput(
                id = requestId,
                title = prompt.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
                message = "",
                defaultValue = prompt.defaultValue.orEmpty(),
                placeholder = placeholder,
                allowClear = true,
                onConfirm = { value -> settle { prompt.confirm(value.trim()) } },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }

    private fun showColorPrompt(prompt: ColorPrompt): GeckoResult<PromptResponse> {
        val initialColor = normalizeHexColor(prompt.defaultValue) ?: "#000000"
        val paletteColors = buildList {
            prompt.predefinedValues.orEmpty().forEach { candidate ->
                normalizeHexColor(candidate)?.let(::add)
            }
            addAll(DEFAULT_WEB_UI_COLOR_PALETTE)
        }.distinct()
        return showComposePrompt(prompt) { requestId, settle ->
            WebUiPromptRequest.ColorPicker(
                id = requestId,
                title = prompt.title?.takeIf { it.isNotBlank() } ?: "选择颜色",
                initialHex = initialColor,
                paletteHexes = paletteColors,
                onConfirm = { chosen ->
                    val normalized = normalizeHexColor(chosen) ?: initialColor
                    settle { prompt.confirm(normalized) }
                },
                onDismiss = { settle { prompt.dismiss() } }
            )
        }
    }

    private fun showComposePrompt(
        prompt: BasePrompt,
        buildRequest: (Long, (() -> PromptResponse) -> Unit) -> WebUiPromptRequest
    ): GeckoResult<PromptResponse> {
        if (prompt.isComplete) return GeckoResult()
        if (released) return GeckoResult.fromValue(prompt.dismiss())

        // 切出新 Compose 弹窗前先按取消收尾旧弹窗，防止网页连弹时出现二次结算异常。
        _activePrompt.value?.onDismiss?.invoke()
        _activePrompt.value = null
        promptController.dismissActiveDialog()

        val requestId = promptIdCounter.getAndIncrement()
        val result = GeckoResult<PromptResponse>()
        val guard = PromptSettlementGuard<PromptResponse>(
            isAlreadyComplete = { prompt.isComplete }
        ) { response ->
            if (_activePrompt.value?.id == requestId) {
                _activePrompt.value = null
            }
            result.complete(response)
        }
        val settle: (() -> PromptResponse) -> Unit = { produce -> guard.settleOnce(produce) }

        // 网页主动撤销弹窗或关闭标签时，同步收起 Compose 弹窗并完成取消结算。
        prompt.delegate = object : PromptInstanceDelegate {
            override fun onPromptDismiss(targetPrompt: BasePrompt) {
                runOnMain { guard.settleOnce { targetPrompt.dismiss() } }
            }
        }
        _activePrompt.value = buildRequest(requestId, settle)
        return result
    }

    private fun toChoiceNodes(choices: Array<Choice>?): List<WebUiChoiceNode> =
        choices.orEmpty().map { choice ->
            WebUiChoiceNode(
                id = choice.id.orEmpty(),
                label = choice.label.orEmpty(),
                disabled = choice.disabled,
                selected = choice.selected,
                separator = choice.separator,
                items = toChoiceNodes(choice.items)
            )
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
}
