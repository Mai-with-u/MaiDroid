package org.maiwithu.maidroid

import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
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

        fileChooser.launcher = fileChooserLauncher
        webView = createWebUiWebView(this).apply {
            webViewClient = WebUiWebViewClient(logTag = MaiBotWebUi.LOG_TAG)
            webChromeClient = WebUiChromeClient(MaiBotWebUi.LOG_TAG, fileChooser)
        }
        setContentView(webView)

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
        val launchUrl = if (MaiBotWebUi.isMaiBotWebUiUrl(url)) {
            MaiBotWebUi.launchUrl(appDependencies.runtimePaths, TerminalLog.logs.value, baseUrl = url)
        } else {
            url
        }
        webView.loadUrl(launchUrl)
    }

    override fun onDestroy() {
        fileChooser.cancelPending()
        webView.destroy()
        super.onDestroy()
    }
}
