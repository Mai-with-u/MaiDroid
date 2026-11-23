package org.maiwithu.maidroid.pet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.FrameLayout
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.terminal.AnsiText
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.webui.MaiBotWebUi

/**
 * 本机悬浮桌宠运行时总控（主线程拥有三个隔离 WebView 与悬浮窗服务的生命周期）：
 * - 固定服务本机 `http://127.0.0.1:8001` 的 MaiBot WebUI，首次自动读取宿主令牌尝试免密登录；
 * - 若用户配置了自定义密码则切入 AuthRequired，交由「扩展组件」页弹窗挂载 auth WebView 完成标准登录；
 * - 登录成功后切入 `/api/host/desktop-pet/session` 轻量会话页，并检测当前机器人 `desktop_pet` 配置，
 *   未开启远端模式时提示并在确认后一键热更新开启，再拉起悬浮窗服务与 WebSocket 通信。
 */
internal class DesktopPetManager(
    context: Context,
    private val settings: AppSettings,
    private val runtimePaths: RuntimePaths,
    private val launcher: PetWindowLauncher
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val assetResolver = PetAssetResolver.from(appContext, runtimePaths)
    private val serverBaseUrl = MaiBotWebUi.URL.trimEnd('/')

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<DesktopPetUiState> = _state.asStateFlow()

    private var overlayHost: PetOverlayHost? = null
    private var authenticated = false
    private var petReady = false
    private var currentConfigState: DesktopPetRemoteConfigState? = null
    private var launchNonce = 0

    private val bridge = DesktopPetBridge(
        appContext = appContext,
        serverBaseUrl = serverBaseUrl,
        assetResolver = assetResolver,
        callbacks = object : DesktopPetBridge.Callbacks {
            override fun isConnected(): Boolean = _state.value.connected
            override fun onAssetSourceResolved(source: PetAssetSource) {
                _state.update { it.copy(assetSource = source) }
            }

            override fun onMainFrameError(message: String) {
                _state.update { it.copy(statusText = message, lastError = message) }
            }

            override fun onRenderGone(role: PetWebRole) {
                TerminalLog.append("[MaiDroid] 桌宠网页渲染进程已退出，已安全停止悬浮桌宠（${role.tagName}）")
                stopOnMain(resetStatus = true)
                _state.update {
                    it.copy(
                        phase = DesktopPetPhase.Error,
                        statusText = "系统回收了网页渲染进程，请重新开启悬浮桌宠",
                        lastError = "网页渲染进程已退出"
                    )
                }
            }

            override fun onOpenExternal(url: String) = openExternalUrl(url)
            override fun onTransportEvent(payload: JSONObject) = handleTransportEvent(payload)
            override fun onPetReady() {
                petReady = true
                startTransportIfReady()
            }

            override fun onPetFit(cssHeight: Double) {
                overlayHost?.fit(cssHeight)
            }

            override fun onPetRegion(rects: JSONArray) {
                overlayHost?.updateTouchableRegion(rects)
            }

            override fun onPetDragBegin() {
                overlayHost?.dragBegin()
            }

            override fun onPetDragMove() {
                overlayHost?.dragMove()
            }

            override fun onPetDragEnd() {
                overlayHost?.dragEnd()
            }

            override fun onFocusChanged(editing: Boolean) {
                overlayHost?.updateInputFocus(editing)
            }

            override fun onOpenExplorationRecords() = openRecords()
            override fun onCloseRecords() = launcher.closeRecords()
            override fun onCopyText(text: String) {
                val clipboard = appContext.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("麦麦", text))
            }

            override fun onExitRequested() = stopOnMain(resetStatus = true)
        }
    )

    private fun initialState(): DesktopPetUiState {
        val hasLocal = assetResolver.hasLocalContainerDesktopPet()
        return DesktopPetUiState(
            assetSource = if (hasLocal) PetAssetSource.LocalContainer else PetAssetSource.BundledFallback,
            containerHasDesktopPet = hasLocal
        )
    }

    /** 刷新容器前端文件同步状态；打开「扩展组件」页或容器更新完后调用。 */
    fun refreshLocalAssetInfo() {
        val hasLocal = assetResolver.hasLocalContainerDesktopPet()
        _state.update { current ->
            val resolvedSource = when {
                current.overlayActive -> current.assetSource
                hasLocal -> PetAssetSource.LocalContainer
                else -> PetAssetSource.BundledFallback
            }
            current.copy(containerHasDesktopPet = hasLocal, assetSource = resolvedSource)
        }
    }

    /** 开启悬浮桌宠：先校验悬浮窗权限 -> 后台读取 WebUI 令牌 -> 主线程拉起认证与传输通道。 */
    fun start() {
        runOnMain { startOnMain() }
    }

    private fun startOnMain() {
        if (!Settings.canDrawOverlays(appContext)) {
            _state.update {
                it.copy(
                    phase = DesktopPetPhase.Error,
                    desiredEnabled = false,
                    statusText = "请先授予「显示在其他应用上层」权限",
                    lastError = "未授予悬浮窗权限"
                )
            }
            return
        }
        val activePhase = _state.value.phase
        if (_state.value.desiredEnabled &&
            (activePhase == DesktopPetPhase.Connecting || activePhase == DesktopPetPhase.Running)
        ) {
            return
        }

        stopOnMain(resetStatus = false)
        val generation = ++launchNonce
        val hasLocal = assetResolver.hasLocalContainerDesktopPet()
        _state.value = DesktopPetUiState(
            phase = DesktopPetPhase.Connecting,
            desiredEnabled = true,
            statusText = "正在连接本机 WebUI…",
            assetSource = if (hasLocal) PetAssetSource.LocalContainer else PetAssetSource.BundledFallback,
            containerHasDesktopPet = hasLocal
        )

        scope.launch {
            val launchUrl = withContext(Dispatchers.IO) {
                val logs = TerminalLog.logs.value.map(AnsiText::plain)
                MaiBotWebUi.launchUrl(runtimePaths, logs, MaiBotWebUi.URL)
            }
            if (generation != launchNonce || !_state.value.desiredEnabled) return@launch
            val view = bridge.createWebView(appContext, PetWebRole.Auth)
            bridge.authView = view
            view.loadUrl(launchUrl)
        }
    }

    /** 断开并重新建立悬浮桌宠连接，绕过活动期防抖保护以恢复异常连接。 */
    fun reconnect() {
        runOnMain {
            if (!Settings.canDrawOverlays(appContext)) {
                startOnMain()
                return@runOnMain
            }
            // 先清理旧会话再重拉连接，避免 startOnMain 被 Connecting/Running 防重入保护拦截
            stopOnMain(resetStatus = true)
            startOnMain()
        }
    }

    /** 断开传输并关闭悬浮桌宠；允许从 IO 线程（如安全退出流程）安全调用。 */
    fun stop() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            stopOnMain(resetStatus = true)
        } else {
            mainHandler.post { stopOnMain(resetStatus = true) }
        }
    }

    private fun stopOnMain(resetStatus: Boolean) {
        launchNonce += 1
        authenticated = false
        petReady = false
        currentConfigState = null
        bridge.resetAndDestroyAll()

        overlayHost?.let { host ->
            overlayHost = null
            host.shutdownFromManager()
        }

        val hasLocal = assetResolver.hasLocalContainerDesktopPet()
        if (resetStatus) {
            _state.value = DesktopPetUiState(
                phase = DesktopPetPhase.Idle,
                desiredEnabled = false,
                statusText = "未开启悬浮桌宠",
                assetSource = if (hasLocal) PetAssetSource.LocalContainer else PetAssetSource.BundledFallback,
                containerHasDesktopPet = hasLocal
            )
        }
    }

    /** 选择或切换目标机器人，并重新检查其 `desktop_pet` 远端配置。 */
    fun selectBot(botId: String) {
        runOnMain {
            if (!DesktopPetProtocol.isValidBotId(botId)) return@runOnMain
            if (_state.value.bots.none { it.botId == botId }) return@runOnMain
            settings.desktopPetBotId = botId
            _state.update {
                it.copy(
                    selectedBotId = botId,
                    connected = false,
                    remoteConfig = null,
                    lastError = null
                )
            }
            if (authenticated) {
                bridge.evalTransport("checkPetStatus", JSONArray().put(botId))
            }
        }
    }

    /** 一键将当前机器人的 `desktop_pet` 配置设为启用与 `remote` 模式并继续连接。 */
    fun enableRemoteModeAndStart() {
        runOnMain {
            val botId = _state.value.selectedBotId
            if (!authenticated || !DesktopPetProtocol.isValidBotId(botId) || _state.value.updatingConfig) {
                return@runOnMain
            }
            _state.update {
                it.copy(
                    updatingConfig = true,
                    statusText = "正在写入远端桌宠配置…",
                    lastError = null
                )
            }
            bridge.evalTransport("enableRemotePet", JSONArray().put(botId))
        }
    }

    /** 切换悬浮窗显示/隐藏，同时通知前端页面调整动画暂停与恢复。 */
    fun toggleVisibility() {
        runOnMain {
            val host = overlayHost ?: return@runOnMain
            val nextVisible = !_state.value.overlayVisible
            host.setOverlayVisible(nextVisible)
            _state.update { it.copy(overlayVisible = nextVisible) }
            bridge.petView?.let { target ->
                bridge.dispatchPageCommand(
                    target = target,
                    id = "vis:" + bridge.generateHexId(),
                    command = JSONObject().put("type", "visibility").put("visible", nextVisible),
                    ack = false
                )
            }
        }
    }

    /** 打开独立的探索记录全屏页。 */
    fun openRecords() {
        runOnMain {
            if (overlayHost == null) return@runOnMain
            launcher.openRecords(appContext)
        }
    }

    /** 由「扩展组件」页登录弹窗挂载 `auth` WebView，以便用户输入密码或处理授权。 */
    fun attachAuthView(container: FrameLayout) {
        runOnMain {
            val view = bridge.authView ?: return@runOnMain
            if (view.parent === container) return@runOnMain
            view.detachFromParent()
            view.alpha = 1f
            container.removeAllViews()
            container.addView(
                view,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    /** 登录弹窗关闭后卸载视图；若已认证且悬浮窗服务在运行，自动归还给后台隐身锚点。 */
    fun detachAuthView(container: FrameLayout) {
        runOnMain {
            val view = bridge.authView ?: return@runOnMain
            if (view.parent === container) {
                container.removeView(view)
            }
            if (authenticated) {
                overlayHost?.attachTransportView(view)
            }
        }
    }

    internal fun bindOverlayService(serviceContext: Context, host: PetOverlayHost): PetWebView? {
        if (!_state.value.desiredEnabled || !Settings.canDrawOverlays(appContext)) return null
        overlayHost = host
        val pet = bridge.createWebView(serviceContext, PetWebRole.Pet)
        bridge.petView = pet
        bridge.authView?.let { host.attachTransportView(it) }
        _state.update { it.copy(overlayActive = true, overlayVisible = true) }
        return pet
    }

    internal fun onServiceDestroyed(host: PetOverlayHost) {
        if (overlayHost === host) {
            overlayHost = null
            if (_state.value.desiredEnabled) {
                stopOnMain(resetStatus = true)
            }
        }
    }

    internal fun createRecordsWebView(activityContext: Context): PetWebView? {
        if (overlayHost == null) return null
        val view = bridge.createWebView(activityContext, PetWebRole.Records)
        bridge.recordsView = view
        return view
    }

    internal fun onRecordsVisibilityChanged(showingRecords: Boolean) {
        overlayHost?.setOverlayVisible(!showingRecords)
    }

    internal fun clearRecordsWebView(view: PetWebView?) {
        bridge.clearRecordsIfMatches(view)
    }

    /** 发送拖起开始事件：按服务端校验要求附带锚点相对按下点的偏移与有限缩放比例，避免 WS 被拒断开。 */
    internal fun sendDragStarted(payload: DesktopPetDragStartPayload) {
        bridge.evalTransport(
            "sendEvent",
            JSONArray().put(
                JSONObject()
                    .put("type", "drag_started")
                    .put("gesture_id", payload.gestureId)
                    .put("offset_x", payload.offsetX)
                    .put("offset_y", payload.offsetY)
                    .put("scale", payload.scale)
            )
        )
    }

    internal fun sendDragEnded(gestureId: String) {
        bridge.evalTransport(
            "sendEvent",
            JSONArray().put(JSONObject().put("type", "drag_ended").put("gesture_id", gestureId))
        )
    }

    internal fun onOverlayInputEditingCheck() {
        bridge.checkInputEditing {
            overlayHost?.updateInputFocus(false)
        }
    }

    private fun handleTransportEvent(payload: JSONObject) {
        when (payload.getString("type")) {
            "ready" -> _state.update { it.copy(connected = false) }
            "authenticated" -> onTransportAuthenticated(payload.getJSONArray("bots"))
            "auth_required" -> {
                authenticated = false
                val reason = payload.optString("reason", "请在 WebUI 中登录")
                _state.update {
                    it.copy(
                        phase = DesktopPetPhase.AuthRequired,
                        connected = false,
                        statusText = reason,
                        lastError = null
                    )
                }
                val view = bridge.authView
                if (view != null && view.url?.contains("/auth") != true) {
                    view.loadUrl("$serverBaseUrl/auth")
                }
            }

            "pet_status" -> onPetStatusReceived(payload)
            "pet_config_updated" -> onPetConfigUpdated(payload)
            "state" -> onTransportState(payload)
            "response" -> bridge.resolvePendingRequest(
                id = payload.getString("id"),
                data = payload.opt("data"),
                error = if (payload.optBoolean("ok")) null else payload.optString("error", "请求失败")
            )

            "command" -> handleRemoteServerCommand(payload)
        }
    }

    private fun onTransportState(payload: JSONObject) {
        val isConnected = payload.getBoolean("connected")
        val statusMsg = payload.optString("text")
        _state.update { current ->
            current.copy(
                phase = if (isConnected) DesktopPetPhase.Running else current.phase,
                connected = isConnected,
                statusText = statusMsg.ifBlank { current.statusText }
            )
        }
        if (statusMsg.isNotBlank()) {
            overlayHost?.updateStatusNotification(statusMsg)
        }
        if (petReady) {
            bridge.petView?.let { target ->
                bridge.dispatchPageCommand(
                    target = target,
                    id = "state:" + bridge.generateHexId(),
                    command = JSONObject()
                        .put("type", "connection")
                        .put("connected", isConnected)
                        .put("text", statusMsg),
                    ack = false
                )
            }
        }
    }

    private fun onTransportAuthenticated(botsJson: JSONArray) {
        val currentAuth = bridge.authView ?: return
        // 认证通过后迁移到同源轻量会话页运行长连接，避免后台常驻完整 React WebUI。
        if (!DesktopPetProtocol.isSessionDocumentUrl(serverBaseUrl, currentAuth.url)) {
            CookieManager.getInstance().flush()
            currentAuth.loadUrl("$serverBaseUrl${DesktopPetProtocol.AUTH_SESSION_PATH}")
            return
        }
        CookieManager.getInstance().flush()
        authenticated = true

        val parsedBots = (0 until botsJson.length()).mapNotNull { idx ->
            val obj = botsJson.optJSONObject(idx) ?: return@mapNotNull null
            val id = obj.optString("bot_id")
            if (!DesktopPetProtocol.isValidBotId(id)) return@mapNotNull null
            DesktopPetBot(
                botId = id,
                name = obj.optString("name").ifBlank { id.take(8) },
                status = obj.optString("status")
            )
        }
        val preferredId = settings.desktopPetBotId
        val nextSelected = parsedBots.firstOrNull { it.botId == preferredId }?.botId
            ?: parsedBots.firstOrNull { it.status == "running" }?.botId
            ?: parsedBots.firstOrNull()?.botId
            ?: ""

        if (nextSelected.isNotEmpty()) {
            settings.desktopPetBotId = nextSelected
        }

        _state.update {
            it.copy(
                bots = parsedBots,
                selectedBotId = nextSelected,
                statusText = if (nextSelected.isEmpty()) "未找到可用机器人实例" else "正在检查机器人桌宠配置…",
                phase = if (nextSelected.isEmpty()) DesktopPetPhase.Error else DesktopPetPhase.Connecting,
                lastError = if (nextSelected.isEmpty()) "当前本机 MaiBot 没有可用的机器人实例" else null
            )
        }
        if (nextSelected.isNotEmpty()) {
            bridge.evalTransport("checkPetStatus", JSONArray().put(nextSelected))
        }
    }

    private fun onPetStatusReceived(payload: JSONObject) {
        val botId = payload.optString("botId")
        if (botId != _state.value.selectedBotId) return
        if (!payload.optBoolean("ok")) {
            val error = payload.optString("error", "无法获取机器人桌宠状态")
            _state.update {
                it.copy(
                    phase = DesktopPetPhase.ConfigRequired,
                    statusText = "暂时无法读取桌宠状态（请确认目标机器人正在运行）",
                    lastError = error
                )
            }
            return
        }
        val statusObj = payload.optJSONObject("status") ?: return
        val cfg = DesktopPetRemoteConfigState(
            enabled = statusObj.optBoolean("enabled", false),
            mode = statusObj.optString("mode", "local"),
            ready = statusObj.optBoolean("ready", false),
            error = statusObj.optString("error", "")
        )
        currentConfigState = cfg

        if (cfg.needsRemoteActivation) {
            _state.update {
                it.copy(
                    phase = DesktopPetPhase.ConfigRequired,
                    remoteConfig = cfg,
                    statusText = "目标机器人尚未开启「远端连接」桌宠模式",
                    updatingConfig = false
                )
            }
            return
        }

        _state.update {
            it.copy(
                phase = if (it.connected) DesktopPetPhase.Running else DesktopPetPhase.Connecting,
                remoteConfig = cfg,
                updatingConfig = false,
                lastError = null
            )
        }
        if (overlayHost == null) {
            _state.update { it.copy(statusText = "正在启动悬浮桌宠窗口…") }
            launcher.startOverlay(appContext)
        } else {
            startTransportIfReady()
        }
    }

    private fun onPetConfigUpdated(payload: JSONObject) {
        val botId = payload.optString("botId")
        if (botId != _state.value.selectedBotId) return
        if (!payload.optBoolean("ok")) {
            val error = payload.optString("error", "保存桌宠配置失败")
            _state.update {
                it.copy(
                    updatingConfig = false,
                    statusText = "开启远端桌宠模式失败：$error",
                    lastError = error
                )
            }
            return
        }
        _state.update { it.copy(updatingConfig = false, statusText = "远端模式已开启，正在校验状态…") }
        mainHandler.postDelayed({
            if (authenticated && _state.value.selectedBotId == botId) {
                bridge.evalTransport("checkPetStatus", JSONArray().put(botId))
            }
        }, 250L)
    }

    private fun startTransportIfReady() {
        val botId = _state.value.selectedBotId
        val cfg = currentConfigState
        if (!authenticated || !petReady || botId.isEmpty() || cfg == null || cfg.needsRemoteActivation) {
            return
        }
        val botName = _state.value.bots.firstOrNull { it.botId == botId }?.name.orEmpty()
        val options = JSONObject()
            .put("botId", botId)
            .put("botName", botName)
            .put("clientId", settings.desktopPetClientId())
            .put("allowScreenRead", false)
        bridge.evalTransport("start", JSONArray().put(options))
    }

    private fun handleRemoteServerCommand(payload: JSONObject) {
        val id = payload.getString("id")
        var action = payload.getJSONObject("command")
        val type = action.getString("type")
        if (type == "capture_screen" || type == "sample_screen" || type == "applications") {
            bridge.evalTransport(
                "commandResult",
                JSONArray().put(id).put(JSONObject.NULL).put("Android 版未启用屏幕读取或应用监测")
            )
            return
        }
        val host = overlayHost
        val pet = bridge.petView
        if (!petReady || host == null || pet == null) {
            bridge.evalTransport(
                "commandResult",
                JSONArray().put(id).put(JSONObject.NULL).put("悬浮桌宠窗口尚未就绪")
            )
            return
        }
        if (type == "hide" || type == "show") {
            val show = type == "show"
            host.setOverlayVisible(show)
            _state.update { it.copy(overlayVisible = show) }
            action = JSONObject().put("type", "visibility").put("visible", show)
        }
        val target = if (type == "exploration_tasks" && action.optString("request_id").startsWith("records:")) {
            bridge.recordsView
        } else {
            pet
        }
        if (target == null) {
            bridge.evalTransport("commandResult", JSONArray().put(id).put(JSONObject()).put(JSONObject.NULL))
            return
        }
        bridge.dispatchPageCommand(target, id, action, ack = true)
    }

    private fun openExternalUrl(value: String) {
        if (!DesktopPetProtocol.isAllowedExternalHttpUrl(value)) return
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, value.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }
}
