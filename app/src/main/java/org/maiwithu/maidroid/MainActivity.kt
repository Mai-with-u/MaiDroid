package org.maiwithu.maidroid

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import org.maiwithu.maidroid.permission.SystemPermissionController
import org.maiwithu.maidroid.remote.RemoteOpsManager
import org.maiwithu.maidroid.ui.MaiDroidApp
import org.maiwithu.maidroid.ui.theme.DarkMaiDroidColors
import org.maiwithu.maidroid.ui.theme.LightMaiDroidColors
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.ui.theme.resolveUseDarkTheme
import org.maiwithu.maidroid.webui.MaiBotWebUi
import org.maiwithu.maidroid.webui.WebUiRuntime

/**
 * 唯一的主界面。界面内容见 MaiDroidApp，流程状态见 MainViewModel；
 * 这里只处理 Activity 层面的事：常亮屏幕、沉浸式系统栏（横屏全屏）、权限回调注册、打开全屏 WebUI。
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val permissions = SystemPermissionController(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = appDependencies.settings
        // 首次引导未完成时保底常亮防止中途息屏中断安装；引导完成后按设置页“屏幕常亮”开关实时控制。
        applyKeepScreenOn(
            window = window,
            keepOn = shouldKeepScreenOn(viewModel.setupComplete.value, settings.keepScreenOn)
        )
        // 在首帧合成前向窗口声明同分辨率最高刷新率，避免高刷屏幕上的网页被默认限帧在 60Hz。
        applyPreferredHighRefreshRate(this)
        val initialDark = resolveUseDarkTheme(settings.themeMode, isSystemDarkMode(resources))
        val initialDarkStatusIcons = shouldUseDarkStatusBarIcons(
            isDarkTheme = initialDark,
            showingSplash = viewModel.showStartupSplash.value,
            setupComplete = viewModel.setupComplete.value
        )
        // 状态栏、导航栏保持透明，并按当前深浅主题与所处页面（插画或主页）设置图标明暗；显隐策略见 applySystemBars。
        enableEdgeToEdge(
            statusBarStyle = if (initialDarkStatusIcons) {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            } else {
                SystemBarStyle.dark(Color.TRANSPARENT)
            },
            navigationBarStyle = if (initialDark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
        )
        window.decorView.setBackgroundColor(
            (if (initialDark) DarkMaiDroidColors.pageBackground else LightMaiDroidColors.pageBackground).toArgb()
        )
        // 首帧排版前先按当前方向设好系统栏：横屏一开始就是全屏，
        // WebUI 页签第一次量到的就是全屏尺寸，桌面版按这个尺寸算布局宽度。
        applySystemBars(
            window = window,
            landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            hideNavigationBar = viewModel.setupComplete.value
        )
        permissions.refresh()
        handleAnnouncementIntent(intent)

        setContent {
            val themeMode by settings.themeModeFlow.collectAsState()
            val isDarkTheme = resolveUseDarkTheme(themeMode, isSystemInDarkTheme())
            val showStartupSplash by viewModel.showStartupSplash.collectAsState()
            val setupComplete by viewModel.setupComplete.collectAsState()
            val keepScreenOn by settings.keepScreenOnFlow.collectAsState()
            val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

            LaunchedEffect(isDarkTheme, showStartupSplash, setupComplete) {
                val darkStatusIcons = shouldUseDarkStatusBarIcons(
                    isDarkTheme = isDarkTheme,
                    showingSplash = showStartupSplash,
                    setupComplete = setupComplete
                )
                enableEdgeToEdge(
                    statusBarStyle = if (darkStatusIcons) {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDarkTheme) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                )
                window.decorView.setBackgroundColor(
                    (if (isDarkTheme) DarkMaiDroidColors.pageBackground else LightMaiDroidColors.pageBackground).toArgb()
                )
            }
            LaunchedEffect(themeMode) {
                WebUiRuntime.updatePreferredColorScheme(themeMode)
            }

            MaiDroidTheme(darkTheme = isDarkTheme) {
                // OOBE 完成、转屏（Activity 没重建时）都重新设一遍系统栏；设置页切换常亮开关时立即增删常亮标记。
                LaunchedEffect(setupComplete, landscape) {
                    applySystemBars(window, landscape = landscape, hideNavigationBar = setupComplete)
                }
                LaunchedEffect(setupComplete, keepScreenOn) {
                    applyKeepScreenOn(window, shouldKeepScreenOn(setupComplete, keepScreenOn))
                }

                // 把系统栏策略传给界面里的弹窗：弹窗是独立窗口，要对自己的窗口套用同一套显隐规则。
                CompositionLocalProvider(LocalHideNavigationBar provides setupComplete) {
                    MaiDroidApp(
                        viewModel = viewModel,
                        permissions = permissions,
                        versionName = BuildConfig.VERSION_NAME,
                        onOpenWebUi = ::openWebUi,
                        onExit = ::finish,
                        // 安全退出进度弹窗的“切到后台”：只把任务退到后台，退出照样在前台服务里做完
                        onMoveToBackground = { moveTaskToBack(true) }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAnnouncementIntent(intent)
    }

    // 清单已接管 uiMode 变更：同步通知 GeckoRuntime，确保跟随系统切浅/深色时网页无需重建即刷新配色。
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        WebUiRuntime.onConfigurationChanged(newConfig)
    }

    // 进入前台时恢复主界面轮询探测并通知电源遥测，已同意隐私授权后立即补报一次实时在线心跳。
    override fun onStart() {
        super.onStart()
        viewModel.onUiForegroundChanged(true)
        appDependencies.powerTelemetry.onUiForegroundChanged(true)
        if (appDependencies.settings.authorizationAccepted) {
            appDependencies.remoteOps.reportOnlineHeartbeat(force = true)
        }
    }

    override fun onStop() {
        viewModel.onUiForegroundChanged(false)
        appDependencies.powerTelemetry.onUiForegroundChanged(false)
        super.onStop()
    }

    // 从系统设置页回来时权限可能变了，重新读一遍，并同步窗口高刷率偏好。
    override fun onResume() {
        super.onResume()
        applyPreferredHighRefreshRate(this)
        permissions.refresh()
        appDependencies.remoteOps.refreshOnForeground()
    }

    private fun handleAnnouncementIntent(intent: Intent?) {
        val annId = intent?.getStringExtra(RemoteOpsManager.EXTRA_ANNOUNCEMENT_ID) ?: return
        intent.removeExtra(RemoteOpsManager.EXTRA_ANNOUNCEMENT_ID)
        appDependencies.remoteOps.focusAnnouncementById(annId)
    }

    private fun openWebUi() {
        startActivity(
            Intent(this, WebUiActivity::class.java).apply {
                putExtra(WebUiActivity.EXTRA_URL, MaiBotWebUi.URL)
            }
        )
    }
}
