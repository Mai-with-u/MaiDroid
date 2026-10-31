package org.maiwithu.maidroid

import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.webui.MaiBotWebUi
import org.maiwithu.maidroid.webui.WebUiChromeClient
import org.maiwithu.maidroid.webui.WebUiFileChooser
import org.maiwithu.maidroid.webui.WebUiWebViewClient
import org.maiwithu.maidroid.webui.createWebUiWebView

/**
 * 全屏打开一个 WebUI 页面：主页唤醒按钮打开 MaiBot WebUI，NapCat 设置按钮打开 NapCat 管理页。
 * MaiBot WebUI 会换成带访问令牌的自动登录地址；返回键先在网页内后退，退到底再关闭页面；
 * 网页里的上传按钮经 WebUiFileChooser 打开系统选文件页。
 */
class WebUiActivity : ComponentActivity() {
    companion object {
        /** 要打开的地址；不传时打开 MaiBot WebUI。 */
        const val EXTRA_URL = "org.maiwithu.maidroid.extra.WEB_UI_URL"
    }

    private lateinit var webView: WebView

    private val fileChooser = WebUiFileChooser(MaiBotWebUi.LOG_TAG)

    // ActivityResult 要在 Activity 启动前注册，所以放在属性里；选完文件把结果交回网页。
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        fileChooser.onResult(result.resultCode, result.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 横屏时和主界面一样隐藏状态栏和导航栏（从边缘滑动临时呼出），竖屏保持系统栏常显。
        applySystemBars(
            window = window,
            landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            hideNavigationBar = false
        )

        fileChooser.launcher = fileChooserLauncher
        val webViewClient = WebUiWebViewClient(logTag = MaiBotWebUi.LOG_TAG)
        webView = createWebUiWebView(this).apply {
            this.webViewClient = webViewClient
            webChromeClient = WebUiChromeClient(MaiBotWebUi.LOG_TAG, fileChooser)
        }
        // 网页放进一层容器，容器按系统栏和挖孔留内边距：Android 15 起强制沉浸式，
        // 不留的话网页会画到状态栏和横屏侧边的摄像头挖孔下面；旧系统由窗口自己避让，这里拿到的是 0。
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(webView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(container)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        finish()
                    }
                }
            }
        )

        val url = intent.getStringExtra(EXTRA_URL) ?: MaiBotWebUi.URL
        val isMaiBotWebUi = MaiBotWebUi.isMaiBotWebUiUrl(url)
        val launchUrl = if (isMaiBotWebUi) {
            MaiBotWebUi.launchUrl(appDependencies.runtimePaths, TerminalLog.logs.value, baseUrl = url)
        } else {
            url
        }
        // 等 WebView 排好版、量出尺寸再加载：横屏时 MaiBot WebUI 要按尺寸算桌面版布局宽度；
        // NapCat 管理页不在这次的桌面版范围内，始终按手机版。
        webView.doOnLayout {
            webViewClient.applyDesktopViewport(webView, enabled = isMaiBotWebUi)
            webView.loadUrl(launchUrl)
        }
    }

    override fun onDestroy() {
        fileChooser.cancelPending()
        webView.destroy()
        super.onDestroy()
    }
}
