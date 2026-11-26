package org.maiwithu.maidroid.ui.home

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.terminal.AnsiText
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.ui.component.LiquidButton
import org.maiwithu.maidroid.ui.component.LiquidButtonStyle
import org.maiwithu.maidroid.ui.component.LocalLiquidModalHost
import org.maiwithu.maidroid.ui.component.WebUiPromptDialogHost
import org.maiwithu.maidroid.ui.component.liquidGlassSurface
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
import org.maiwithu.maidroid.ui.component.statusBarTopInset
import org.maiwithu.maidroid.ui.theme.CardSurface
import org.maiwithu.maidroid.ui.theme.Corners
import org.maiwithu.maidroid.ui.theme.LiquidBarSurface
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.StatusWarning
import org.maiwithu.maidroid.ui.theme.TextPrimary
import org.maiwithu.maidroid.ui.theme.TextSizes
import org.maiwithu.maidroid.webui.MaiBotWebUi
import org.maiwithu.maidroid.webui.WebUiDownloads
import org.maiwithu.maidroid.webui.WebUiFileChooser
import org.maiwithu.maidroid.webui.WebUiRenderCrashGuard
import org.maiwithu.maidroid.webui.WebUiSessionHost

/** 主页或全屏网页退后台连续空闲这么久后销毁 WebUI 会话，释放内容进程与显存；回到前台且选中页签再按需唤醒。 */
internal const val WEB_UI_HIBERNATE_DELAY_MS = 2 * 60_000L
private const val WEB_UI_HIBERNATE_RETRY_MS = 60_000L

/** 仅当主界面处于前台且当前正停留在 WebUI 页签时，GeckoSession 才维持活跃渲染。 */
internal fun shouldWebUiSessionBeActive(isStarted: Boolean, isWebUiTabSelected: Boolean): Boolean =
    isStarted && isWebUiTabSelected

/**
 * 退后台期间系统触发低内存告警时立即清理空闲 WebUI：
 * 宿主因前台服务常驻同进程而不会进入 LRU 后台队列，实际收到的是 RUNNING_LOW / RUNNING_CRITICAL；
 * 同时兼容无前台服务时的 >= TRIM_MEMORY_BACKGROUND 档位，并跳过每次切桌面都会派发的 TRIM_MEMORY_UI_HIDDEN。
 */
@Suppress("DEPRECATION")
internal fun shouldHibernateWebUiOnTrimMemory(level: Int, isStarted: Boolean): Boolean {
    if (isStarted) return false
    return level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
        level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
}

/**
 * WebUI 页签的 GeckoView 会话宿主及其加载状态；放在主页外壳里记住，切换标签时不销毁。
 * 网页内容进程没了时用 createSessionHost 换一个新的宿主：旧的拿下来释放 -> 建新的 -> 页签重新挂载并加载；
 * 短时间内反复崩溃就停下，等用户点“重试”。
 * 应用退到后台空闲超过 [WEB_UI_HIBERNATE_DELAY_MS] 且无挂起的选文件/另存为回调时释放会话休眠省内存，
 * 回到前台自动重建重新连接本机 WebUI。
 */
internal class WebUiTabState internal constructor(
    val fileChooser: WebUiFileChooser,
    val downloads: WebUiDownloads,
    private val createSessionHost: (WebUiTabState) -> WebUiSessionHost
) {
    private val renderCrashGuard = WebUiRenderCrashGuard()

    /** 现在挂在页签上的 GeckoView 宿主；退后台休眠期间为 null，回到前台或刷新时按需重建。 */
    var sessionHost by mutableStateOf<WebUiSessionHost?>(createSessionHost(this))
        private set

    var webUiError by mutableStateOf<String?>(null)
    var reloadToken by mutableIntStateOf(0)
    var viewHasSize by mutableStateOf(false)

    /** 内容进程反复崩溃、暂停了自动加载；点“重试”后恢复。 */
    var autoLoadPaused by mutableStateOf(false)
        private set

    /** 网页自己的底色（由内置扩展通过原生消息上报）；还没报上来或加载失败时为 null。横屏时涂在侧栏底下让出的那一条。 */
    var pageBackground by mutableStateOf<Color?>(null)

    /** 清掉错误提示并重新加载：若处于休眠态则先唤醒建新实例，再换加载令牌触发重新加载。 */
    fun reload() {
        ensureAwake()
        webUiError = null
        autoLoadPaused = false
        reloadToken += 1
    }

    /**
     * 退后台空闲超时后销毁会话让渲染子进程退出释放内存；
     * 若此时用户正在系统选文件或“另存为”界面中（hasPending），暂不休眠，交由调用方稍后重试。
     */
    internal fun hibernateIfIdle(): Boolean {
        val current = sessionHost ?: return false
        if (fileChooser.hasPending || downloads.hasPending) return false
        current.release()
        sessionHost = null
        viewHasSize = false
        pageBackground = null
        Log.d(MaiBotWebUi.LOG_TAG, "WebUI 退后台空闲超时，已休眠释放内容进程")
        return true
    }

    /** 回到前台或需要重新加载时唤醒已休眠的会话宿主：重置尺寸与背景标记，让页签排好版后自动重连。 */
    internal fun ensureAwake() {
        if (sessionHost != null) return
        attachFreshSessionHost()
        webUiError = null
        Log.d(MaiBotWebUi.LOG_TAG, "WebUI 已从休眠恢复")
    }

    // 内容进程没了：这个宿主已经不能用，换一个新的。两分钟里崩了三次以上就不自动加载，显示“重试”。
    internal fun replaceDeadSessionHost(dead: WebUiSessionHost) {
        if (dead !== sessionHost) return
        dead.release()
        attachFreshSessionHost()
        if (renderCrashGuard.shouldAutoReopen()) {
            webUiError = null
        } else {
            autoLoadPaused = true
            webUiError = "网页进程反复崩溃，已暂停自动加载"
        }
    }

    // 重建并挂载一个新的会话宿主：尺寸和背景标记同步清零，让页签量出真实视口尺寸后再加载。
    private fun attachFreshSessionHost() {
        val nextHost = createSessionHost(this)
        viewHasSize = false
        pageBackground = null
        sessionHost = nextHost
    }
}

/** 创建 WebUI 页签的 GeckoView 会话宿主，切页或退后台时即时冻结，退后台超时或系统紧缩内存时休眠释放。 */
@Composable
internal fun rememberWebUiTabState(
    isWebUiTabSelected: Boolean = true
): WebUiTabState {
    val context = LocalContext.current
    val state = remember(context) {
        val fileChooser = WebUiFileChooser(context, MaiBotWebUi.LOG_TAG)
        val downloads = WebUiDownloads(context, MaiBotWebUi.LOG_TAG)

        // 首次构建与内容进程异常退出后重建都共用这套会话工厂；回调里经 tabState 更新页签状态。
        WebUiTabState(fileChooser, downloads) { tabState ->
            WebUiSessionHost(
                context = context,
                logTag = MaiBotWebUi.LOG_TAG,
                fileChooser = fileChooser,
                downloads = downloads,
                onPageFinished = { tabState.webUiError = null },
                onMainFrameError = { errorText ->
                    tabState.webUiError = errorText
                    tabState.pageBackground = null
                    // 主框架报错同步落进终端日志，方便群友反馈“无法连接”时从诊断包回溯具体原因。
                    TerminalLog.append("[MaiDroid] WebUI 加载失败（$errorText）")
                },
                onPageBackgroundColor = { argb -> tabState.pageBackground = Color(argb) },
                onContentProcessGone = tabState::replaceDeadSessionHost
            )
        }
    }

    // 网页点上传按钮 -> fileChooser 用这个 launcher 打开系统选文件页 -> 选完回到这里，把结果交回网页。
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        state.fileChooser.onResult(result.resultCode, result.data)
    }
    // 网页点导出 -> downloads 用这个 launcher 弹“另存为” -> 选好位置回到这里，交给 downloads 写文件。
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        state.downloads.onResult(result.resultCode, result.data)
    }
    SideEffect {
        state.fileChooser.launcher = fileChooserLauncher
        state.downloads.launcher = downloadLauncher
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isStarted by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> isStarted = true
                Lifecycle.Event.ON_STOP -> isStarted = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 切离 WebUI 页签或退后台时立即冻结渲染与页面定时器；回到 WebUI 页签才按需从休眠态懒唤醒。
    val shouldBeActive = shouldWebUiSessionBeActive(isStarted, isWebUiTabSelected)
    LaunchedEffect(state.sessionHost, shouldBeActive) {
        state.sessionHost?.setActive(shouldBeActive)
    }

    // 退到后台持续满 2 分钟再休眠，避免用户短暂切去聊天软件或选文件时反复销毁重建会话；回前景且选中页签再唤醒。
    LaunchedEffect(isStarted, isWebUiTabSelected, state) {
        if (isStarted) {
            if (isWebUiTabSelected) {
                state.ensureAwake()
            }
        } else {
            delay(WEB_UI_HIBERNATE_DELAY_MS)
            while (state.sessionHost != null && !state.hibernateIfIdle()) {
                delay(WEB_UI_HIBERNATE_RETRY_MS)
            }
        }
    }

    // 退后台期间若收到系统内存紧张信号，立即交还渲染子进程内存，优先保全容器与前台服务不被 LMK 查杀。
    val latestIsStarted by rememberUpdatedState(isStarted)
    DisposableEffect(context, state) {
        val appContext = context.applicationContext
        val mainHandler = Handler(Looper.getMainLooper())
        val trimCallback = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (shouldHibernateWebUiOnTrimMemory(level, latestIsStarted)) {
                    mainHandler.post { state.hibernateIfIdle() }
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                if (!latestIsStarted) {
                    mainHandler.post { state.hibernateIfIdle() }
                }
            }
        }
        appContext.registerComponentCallbacks(trimCallback)
        onDispose {
            mainHandler.removeCallbacksAndMessages(null)
            appContext.unregisterComponentCallbacks(trimCallback)
        }
    }

    DisposableEffect(state) {
        onDispose {
            // 先把没回复的选文件请求按取消结束、丢掉还没选位置的下载，再释放仍在存活的 GeckoView 会话。
            state.fileChooser.launcher = null
            state.fileChooser.cancelPending()
            state.downloads.release()
            state.sessionHost?.release()
        }
    }

    return state
}

/**
 * WebUI 页签：嵌入式加载 MaiBot WebUI（带访问令牌自动登录），视图拿到非零尺寸后才开始加载。
 * 每次加载前按尺寸决定排版：横屏按桌面版（布局宽度至少 1040），竖屏按手机版。
 * 顶部按情况显示“加载失败，重试”或“WebUI 离线，唤醒”（电量低暂停着时说明是电量低）；刷新操作统一在竖屏侧边灵动胶囊与横屏侧栏里提供，不再单独遮挡右上角。
 * startClearance：横屏时左侧悬浮侧栏的宽度，网页和横幅都让开它，让出的那一条涂网页自己的底色。
 */
@Composable
internal fun WebUiTabPage(
    webUiOnline: Boolean,
    onWakeMai: () -> Unit,
    state: WebUiTabState,
    modifier: Modifier = Modifier,
    lowBatteryPaused: Boolean = false,
    terminalLogs: List<String> = emptyList(),
    url: String = MaiBotWebUi.URL,
    startClearance: Dp = 0.dp
) {
    val context = LocalContext.current
    val statusBarTop = statusBarTopInset()

    // 弹出键盘时容器底部让出键盘的高度。主界面是全面屏（edge-to-edge），清单里的 adjustResize 不再缩小窗口，
    // 按键盘的最终高度（imeAnimationTarget）只缩一次，避免输入重排卡顿。
    @OptIn(ExperimentalLayoutApi::class)
    val imeClearance = WindowInsets.imeAnimationTarget.only(WindowInsetsSides.Bottom)

    // 麦麦重启的那十几秒里页签刚好加载失败时，WebUI 重新上线就自动重载一次。
    LaunchedEffect(webUiOnline) {
        if (webUiOnline && state.webUiError != null) {
            state.reload()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // 横屏：整页先涂网页底色 -> 左边让出侧栏宽度 -> 余下部分照旧铺页面底色、放网页和横幅。
            // 侧栏的毛玻璃底下透出的就是网页的颜色，切换标签时这一条跟着网页一起滑走；网页还没报上底色时用页面底色。
            .background(state.pageBackground ?: PageBackground)
            .padding(start = startClearance)
            .background(PageBackground)
    ) {
        // 换了新会话宿主（内容进程没了或休眠唤醒）时 key 变了，旧的 AndroidView 撤掉、按新的重新挂上并加载。
        val activeHost = state.sessionHost
        // 登录令牌文件读取与日志扫描放到 IO 线程按需解析，避免 terminalLogs 高频刷新时拖慢主线程导致 ANR。
        var resolvedLaunch by remember(url) { mutableStateOf<Pair<Int, String>?>(null) }

        LaunchedEffect(url, state.reloadToken, activeHost, state.viewHasSize, webUiOnline) {
            if (activeHost == null || !state.viewHasSize) return@LaunchedEffect
            val requestToken = state.reloadToken
            val paths = context.appDependencies.runtimePaths
            val computed = withContext(Dispatchers.IO) {
                val logs = TerminalLog.logs.value.map(AnsiText::plain).ifEmpty { terminalLogs }
                MaiBotWebUi.launchUrl(paths = paths, terminalLogs = logs, baseUrl = url)
            }
            resolvedLaunch = requestToken to computed
        }

        // 离线或加载失败横幅改用基于网页底色（冷启动未上报时兜底 CardSurface）的零开销采样背景；仅弹窗打开时临时启用离屏层。
        val bannerContentBackdrop = rememberSurfaceBackdrop(state.pageBackground ?: CardSurface)
        val hasActiveModal = LocalLiquidModalHost.current?.hasModals == true

        if (activeHost != null) {
            val activePrompt by activeHost.activePrompt.collectAsState()
            Box(modifier = Modifier.fillMaxSize()) {
                key(activeHost) {
                    AndroidView(
                        factory = {
                            val hostView = activeHost.view
                            (hostView.parent as? ViewGroup)?.removeView(hostView)
                            hostView
                        },
                        update = { geckoView ->
                            val launch = resolvedLaunch
                            if (state.viewHasSize &&
                                !state.autoLoadPaused &&
                                launch != null &&
                                launch.first == state.reloadToken
                            ) {
                                val launchUrl = launch.second
                                val loadRequest = "$launchUrl#${launch.first}"
                                if (geckoView.tag != loadRequest) {
                                    geckoView.tag = loadRequest
                                    state.webUiError = null
                                    // 首次加载、点刷新或重试时都按此刻的尺寸重新决定桌面版还是手机版；
                                    // 转屏会重建 Activity 与会话，新页面自然按新方向加载。
                                    activeHost.applyDesktopViewport(enabled = true)
                                    Log.d(
                                        MaiBotWebUi.LOG_TAG,
                                        "load ${MaiBotWebUi.redactUrlForLogs(launchUrl)} at " +
                                            "${geckoView.width}x${geckoView.height}, " +
                                            "desktop width=${activeHost.desktopLayoutWidthCss}"
                                    )
                                    activeHost.loadUrl(launchUrl)
                                }
                            }
                        },
                        modifier = Modifier
                            .padding(top = statusBarTop)
                            .windowInsetsPadding(imeClearance)
                            .fillMaxSize()
                            // 仅在弹出液态玻璃模态卡片需要录制整页采样时开启离屏合成，防止常规浏览每帧分配全屏 FBO 拖慢帧率。
                            .then(if (hasActiveModal) Modifier.graphicsLayer { alpha = 0.999f } else Modifier)
                            .onSizeChanged { size ->
                                state.viewHasSize = size.width > 0 && size.height > 0
                            }
                    )
                }
            }
            WebUiPromptDialogHost(prompt = activePrompt)
        }

        if (state.webUiError != null) {
            WebUiStatusBanner(
                backdrop = bannerContentBackdrop,
                message = "WebUI 加载失败：${state.webUiError}",
                action = "重试",
                onAction = state::reload,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBarTop + 8.dp, start = 16.dp, end = 16.dp)
            )
        } else if (!webUiOnline) {
            // 电量低暂停着时麦麦是主动保存数据停下的，横幅直接说明原因
            WebUiStatusBanner(
                backdrop = bannerContentBackdrop,
                message = if (lowBatteryPaused) "电量低，麦麦已暂停" else "WebUI 离线",
                action = "唤醒",
                onAction = onWakeMai,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = statusBarTop + 8.dp, start = 16.dp, end = 16.dp)
            )
        }
    }
}

@Composable
private fun WebUiStatusBanner(
    backdrop: Backdrop,
    message: String,
    action: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bannerSurfaceBackdrop = rememberLayerBackdrop()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassSurface(
                backdrop = backdrop,
                shape = { RoundedRectangle(Corners.Chip) },
                blurRadius = 12.dp,
                refractionHeight = 14.dp,
                refractionAmount = 22.dp,
                surfaceColor = LiquidBarSurface,
                exportedBackdrop = bannerSurfaceBackdrop
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(StatusWarning)
        )

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = message,
            color = TextPrimary,
            fontSize = TextSizes.Body,
            fontWeight = FontWeight.Bold,
            lineHeight = 16.sp,
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(10.dp))

        LiquidButton(
            onClick = onAction,
            backdrop = bannerSurfaceBackdrop,
            style = LiquidButtonStyle.SoftAccent,
            minHeight = 34.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = action,
                fontSize = TextSizes.Caption,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
