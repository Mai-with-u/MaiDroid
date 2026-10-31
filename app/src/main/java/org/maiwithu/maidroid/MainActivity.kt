package org.maiwithu.maidroid

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalConfiguration
import org.maiwithu.maidroid.permission.SystemPermissionController
import org.maiwithu.maidroid.ui.MaiDroidApp
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.webui.MaiBotWebUi

/**
 * 唯一的主界面。界面内容见 MaiDroidApp，流程状态见 MainViewModel；
 * 这里只处理 Activity 层面的事：常亮屏幕、沉浸式系统栏（横屏全屏）、权限回调注册、打开全屏 WebUI。
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val permissions = SystemPermissionController(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 状态栏、导航栏都透明并用浅色图标（深色界面）；什么时候显示、隐藏见 applySystemBars。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        // 首帧排版前先按当前方向设好系统栏：横屏一开始就是全屏，
        // WebUI 页签第一次量到的就是全屏尺寸，桌面版按这个尺寸算布局宽度。
        applySystemBars(
            window = window,
            landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            hideNavigationBar = viewModel.setupComplete.value
        )
        permissions.refresh()

        setContent {
            MaiDroidTheme {
                val setupComplete by viewModel.setupComplete.collectAsState()
                val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
                // OOBE 完成、转屏（Activity 没重建时）都重新设一遍系统栏。
                LaunchedEffect(setupComplete, landscape) {
                    applySystemBars(window, landscape = landscape, hideNavigationBar = setupComplete)
                }

                MaiDroidApp(
                    viewModel = viewModel,
                    permissions = permissions,
                    versionName = BuildConfig.VERSION_NAME,
                    onOpenWebUi = ::openWebUi,
                    onExit = ::finish
                )
            }
        }
    }

    // 从系统设置页回来时权限可能变了，重新读一遍。
    override fun onResume() {
        super.onResume()
        permissions.refresh()
    }

    private fun openWebUi() {
        startActivity(
            Intent(this, WebUiActivity::class.java).apply {
                putExtra(WebUiActivity.EXTRA_URL, MaiBotWebUi.URL)
            }
        )
    }
}
