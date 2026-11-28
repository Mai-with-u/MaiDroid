package org.maiwithu.maidroid.pet

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.applySystemBarAppearance
import org.maiwithu.maidroid.isSystemDarkMode
import org.maiwithu.maidroid.ui.theme.DarkMaiDroidColors
import org.maiwithu.maidroid.ui.theme.LightMaiDroidColors
import org.maiwithu.maidroid.ui.theme.resolveUseDarkTheme

/**
 * 悬浮桌宠探索记录独立查看页：打开时自动暂隐悬浮桌宠以免遮挡列表，退出后恢复显示。
 */
class PetRecordsActivity : ComponentActivity() {

    companion object {
        private var current: PetRecordsActivity? = null

        fun closeIfOpen() {
            current?.finish()
        }
    }

    private var recordsView: PetWebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = appDependencies.desktopPet
        val webView = manager.createRecordsWebView(this)
        if (webView == null) {
            finish()
            return
        }
        current = this
        recordsView = webView

        val isDarkTheme = resolveUseDarkTheme(appDependencies.settings.themeMode, isSystemDarkMode(resources))
        val pageBg = (if (isDarkTheme) DarkMaiDroidColors.pageBackground else LightMaiDroidColors.pageBackground).toArgb()
        window.decorView.setBackgroundColor(pageBg)
        applySystemBarAppearance(
            window = window,
            darkStatusBarIcons = !isDarkTheme,
            darkNavigationBarIcons = !isDarkTheme
        )

        val container = FrameLayout(this).apply {
            setBackgroundColor(pageBg)
            addView(
                webView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(container)
        webView.loadUrl(DesktopPetProtocol.RECORDS_PAGE_URL)
    }

    override fun onResume() {
        super.onResume()
        appDependencies.desktopPet.onRecordsVisibilityChanged(true)
    }

    override fun onPause() {
        appDependencies.desktopPet.onRecordsVisibilityChanged(false)
        super.onPause()
    }

    override fun onDestroy() {
        if (current === this) current = null
        appDependencies.desktopPet.clearRecordsWebView(recordsView)
        recordsView = null
        super.onDestroy()
    }
}
