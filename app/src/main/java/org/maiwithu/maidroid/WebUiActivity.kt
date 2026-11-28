package org.maiwithu.maidroid

import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.kyant.backdrop.Backdrop
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.ui.component.LiquidModalHostOverlay
import org.maiwithu.maidroid.ui.component.LiquidModalHostState
import org.maiwithu.maidroid.ui.component.LocalLiquidModalHost
import org.maiwithu.maidroid.ui.component.WebUiPromptDialogHost
import org.maiwithu.maidroid.ui.component.rememberLiquidModalHostState
import org.maiwithu.maidroid.ui.component.rememberSurfaceBackdrop
import org.maiwithu.maidroid.ui.component.setFocusGuardedContent
import org.maiwithu.maidroid.ui.home.WEB_UI_HIBERNATE_DELAY_MS
import org.maiwithu.maidroid.ui.home.shouldHibernateWebUiOnTrimMemory
import org.maiwithu.maidroid.ui.theme.DarkMaiDroidColors
import org.maiwithu.maidroid.ui.theme.LightMaiDroidColors
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.PageBackground
import org.maiwithu.maidroid.ui.theme.resolveUseDarkTheme
import org.maiwithu.maidroid.webui.MaiBotWebUi
import org.maiwithu.maidroid.webui.WebUiDownloads
import org.maiwithu.maidroid.webui.WebUiFileChooser
import org.maiwithu.maidroid.webui.WebUiPromptRequest
import org.maiwithu.maidroid.webui.WebUiRenderCrashGuard
import org.maiwithu.maidroid.webui.WebUiRuntime
import org.maiwithu.maidroid.webui.WebUiSessionHost
import android.graphics.Color as AndroidColor

/**
 * 全屏打开一个 WebUI 页面：主页唤醒按钮打开 MaiBot WebUI，NapCat / SnowLuma 设置按钮打开对应管理页。
 * 外壳采用 Compose + 液态玻璃模态宿主以承接网页原生表单弹窗的实时玻璃折射，
 * 并继续保留令牌自动登录、上传/导出桥接、2 分钟后台休眠与进程崩溃恢复逻辑。
 */
class WebUiActivity : ComponentActivity() {
    companion object {
        /** 要打开的地址；不传时打开 MaiBot WebUI。 */
        const val EXTRA_URL = "org.maiwithu.maidroid.extra.WEB_UI_URL"

        // 放在伴生对象里：整页重建后还是同一份计数，才认得出“反复崩溃”
        private val renderCrashGuard = WebUiRenderCrashGuard()
        private const val HIBERNATE_RETRY_DELAY_MS = 60_000L
    }

    private var sessionHostState by mutableStateOf<WebUiSessionHost?>(null)
    private lateinit var fileChooser: WebUiFileChooser
    private var isMaiBotWebUi = true
    private var initialLaunchUrl = MaiBotWebUi.URL
    private var activityStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hibernateRunnable = object : Runnable {
        override fun run() {
            if (activityStarted) return
            if (!hibernateSessionIfIdle()) {
                // 若文件选择或导出下载尚未结束，顺延重试，避免在系统选文件时拆掉会话。
                mainHandler.postDelayed(this, HIBERNATE_RETRY_DELAY_MS)
            }
        }
    }

    // ActivityResult 要在 Activity 启动前注册，所以放在属性里；选完文件把结果交回网页。
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileChooser.onResult(result.resultCode, result.data)
    }

    // 下载要用应用上下文，属性初始化时 Activity 还没有，所以在 onCreate 里建。
    private lateinit var downloads: WebUiDownloads

    // 网页点导出 -> downloads 用这个 launcher 弹“另存为” -> 选好位置回到这里，交给 downloads 写文件。
    private val downloadLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        downloads.onResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = appDependencies.settings
        // 全屏网页同样遵循设置页的“屏幕常亮”开关，默认允许自动息屏，并在首帧前声明高刷新率。
        applyKeepScreenOn(window, settings.keepScreenOn)
        applyPreferredHighRefreshRate(this)
        val initialDark = resolveUseDarkTheme(settings.themeMode, isSystemDarkMode(resources))
        val barStyle = if (initialDark) {
            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        } else {
            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        }
        enableEdgeToEdge(
            statusBarStyle = barStyle,
            navigationBarStyle = barStyle
        )
        window.decorView.setBackgroundColor(
            (if (initialDark) DarkMaiDroidColors.pageBackground else LightMaiDroidColors.pageBackground).toArgb()
        )
        // 横屏时和主界面一样隐藏状态栏和导航栏（从边缘滑动临时呼出），竖屏保持系统栏常显。
        applySystemBars(
            window = window,
            landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            hideNavigationBar = false
        )

        fileChooser = WebUiFileChooser(this, MaiBotWebUi.LOG_TAG).apply { launcher = fileChooserLauncher }
        downloads = WebUiDownloads(this, MaiBotWebUi.LOG_TAG).apply { launcher = downloadLauncher }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val host = sessionHostState
                    if (host != null && host.canGoBack) {
                        host.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )

        val url = intent.getStringExtra(EXTRA_URL) ?: MaiBotWebUi.URL
        isMaiBotWebUi = MaiBotWebUi.isMaiBotWebUiUrl(url)
        initialLaunchUrl = if (isMaiBotWebUi) {
            MaiBotWebUi.launchUrl(appDependencies.runtimePaths, TerminalLog.logs.value, baseUrl = url)
        } else {
            url
        }
        attachAndLoadSessionHost()

        // 统一挂载焦点防重入根容器，防止休眠或替换 GeckoView 会话时同步触发 rootViewRequestFocus 重入。
        setFocusGuardedContent {
            val themeMode by settings.themeModeFlow.collectAsState()
            val isDarkTheme = resolveUseDarkTheme(themeMode, isSystemInDarkTheme())
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

            LaunchedEffect(isDarkTheme) {
                val activeBarStyle = if (isDarkTheme) {
                    SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                } else {
                    SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                }
                enableEdgeToEdge(
                    statusBarStyle = activeBarStyle,
                    navigationBarStyle = activeBarStyle
                )
                window.decorView.setBackgroundColor(
                    (if (isDarkTheme) DarkMaiDroidColors.pageBackground else LightMaiDroidColors.pageBackground).toArgb()
                )
            }
            LaunchedEffect(themeMode) {
                WebUiRuntime.updatePreferredColorScheme(themeMode)
            }

            MaiDroidTheme(darkTheme = isDarkTheme) {
                LaunchedEffect(landscape) {
                    applySystemBars(window, landscape = landscape, hideNavigationBar = false)
                }

                val modalHost = rememberLiquidModalHostState()
                // GeckoView 使用 SurfaceView 硬件直出，液态弹窗改用零离屏拷贝页面底色背景源
                val webBackdrop = rememberSurfaceBackdrop(PageBackground)
                val activeHost = sessionHostState
                LaunchedEffect(activeHost, isDarkTheme) {
                    activeHost?.updateThemeBackground(isDarkTheme)
                }
                val activePrompt = if (activeHost != null) {
                    activeHost.activePrompt.collectAsState().value
                } else {
                    null
                }

                CompositionLocalProvider(
                    LocalHideNavigationBar provides false,
                    LocalLiquidModalHost provides modalHost
                ) {
                    WebUiActivityScreen(
                        activeHost = activeHost,
                        activePrompt = activePrompt,
                        modalHost = modalHost,
                        webBackdrop = webBackdrop,
                        initialLaunchUrl = initialLaunchUrl,
                        isMaiBotWebUi = isMaiBotWebUi
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activityStarted = true
        mainHandler.removeCallbacks(hibernateRunnable)
        val current = sessionHostState
        if (current == null) {
            attachAndLoadSessionHost()
        } else {
            current.setActive(true)
        }
        appDependencies.powerTelemetry.onUiForegroundChanged(true)
    }

    override fun onResume() {
        super.onResume()
        applyPreferredHighRefreshRate(this)
        applyKeepScreenOn(window, appDependencies.settings.keepScreenOn)
    }

    // 清单已接管 uiMode 变更：系统切换深浅色时同步通知 GeckoRuntime，维持内置网页媒体查询一致。
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WebUiRuntime.onConfigurationChanged(newConfig)
    }

    override fun onStop() {
        activityStarted = false
        sessionHostState?.setActive(false)
        mainHandler.removeCallbacks(hibernateRunnable)
        mainHandler.postDelayed(hibernateRunnable, WEB_UI_HIBERNATE_DELAY_MS)
        appDependencies.powerTelemetry.onUiForegroundChanged(false)
        super.onStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 退后台期间系统触发内存回收时主动关闭空闲网页渲染进程，减轻主进程及容器 LMK 压力。
        if (shouldHibernateWebUiOnTrimMemory(level, isStarted = activityStarted)) {
            mainHandler.removeCallbacks(hibernateRunnable)
            hibernateSessionIfIdle()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        fileChooser.cancelPending()
        downloads.release()
        sessionHostState?.release()
        sessionHostState = null
        super.onDestroy()
    }

    // 创建或从休眠恢复全屏 GeckoView 会话；拿到非零布局尺寸后再在 Compose update 中触发加载。
    private fun attachAndLoadSessionHost() {
        if (sessionHostState != null) return
        sessionHostState = WebUiSessionHost(
            context = this,
            logTag = MaiBotWebUi.LOG_TAG,
            fileChooser = fileChooser,
            downloads = downloads,
            onContentProcessGone = { dead ->
                (dead.view.parent as? ViewGroup)?.removeView(dead.view)
                dead.release()
                if (sessionHostState === dead) sessionHostState = null
                if (renderCrashGuard.shouldAutoReopen()) {
                    recreate()
                } else {
                    Toast.makeText(this, "网页反复崩溃，已关闭这个页面", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        )
    }

    private fun hibernateSessionIfIdle(): Boolean {
        val current = sessionHostState ?: return true
        if (fileChooser.hasPending || downloads.hasPending) return false
        current.release()
        sessionHostState = null
        return true
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebUiActivityScreen(
    activeHost: WebUiSessionHost?,
    activePrompt: WebUiPromptRequest?,
    modalHost: LiquidModalHostState,
    webBackdrop: Backdrop,
    initialLaunchUrl: String,
    isMaiBotWebUi: Boolean
) {
    val safeInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val imeClearance = WindowInsets.imeAnimationTarget.only(WindowInsetsSides.Bottom)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PageBackground)
    ) {
        if (activeHost != null) {
            Box(modifier = Modifier.fillMaxSize()) {
                key(activeHost) {
                    var viewHasSize by remember(activeHost) { mutableStateOf(false) }
                    AndroidView(
                        factory = {
                            val hostView = activeHost.view
                            (hostView.parent as? ViewGroup)?.removeView(hostView)
                            hostView
                        },
                        update = { geckoView ->
                            if (viewHasSize && geckoView.tag != initialLaunchUrl) {
                                geckoView.tag = initialLaunchUrl
                                activeHost.applyDesktopViewport(enabled = isMaiBotWebUi)
                                activeHost.loadUrl(initialLaunchUrl)
                            }
                        },
                        modifier = Modifier
                            .windowInsetsPadding(safeInsets)
                            .windowInsetsPadding(imeClearance)
                            .fillMaxSize()
                            .onSizeChanged { size ->
                                viewHasSize = size.width > 0 && size.height > 0
                            }
                    )
                }
            }
            WebUiPromptDialogHost(prompt = activePrompt)
        }

        LiquidModalHostOverlay(
            host = modalHost,
            backdrop = webBackdrop
        )
    }
}
