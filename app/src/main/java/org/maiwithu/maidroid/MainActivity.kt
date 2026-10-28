package org.maiwithu.maidroid

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.maiwithu.maidroid.permission.SystemPermissionController
import org.maiwithu.maidroid.ui.MaiDroidApp
import org.maiwithu.maidroid.ui.theme.MaiDroidTheme
import org.maiwithu.maidroid.webui.MaiBotWebUi

/**
 * 唯一的主界面。界面内容见 MaiDroidApp，流程状态见 MainViewModel；
 * 这里只处理 Activity 层面的事：常亮屏幕、沉浸式系统栏、权限回调注册、打开全屏 WebUI。
 */
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val permissions = SystemPermissionController(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 状态栏、导航栏都透明并用浅色图标（深色界面）；OOBE 期间显示、进入主页后隐藏导航栏见 configureSystemBars。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        permissions.refresh()

        setContent {
            MaiDroidTheme {
                val setupComplete by viewModel.setupComplete.collectAsState()
                LaunchedEffect(setupComplete) {
                    configureSystemBars(setupComplete)
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

    // OOBE 期间显示完整系统栏；进入主页后隐藏导航栏（上滑临时呼出），状态栏一直显示。
    private fun configureSystemBars(setupComplete: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.statusBars())

        if (setupComplete) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun openWebUi() {
        startActivity(
            Intent(this, WebUiActivity::class.java).apply {
                putExtra(WebUiActivity.EXTRA_URL, MaiBotWebUi.URL)
            }
        )
    }
}
