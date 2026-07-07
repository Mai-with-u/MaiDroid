package org.maiwithu.maidroid

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import org.maiwithu.maidroid.container.MaiBotContainerConfig
import org.maiwithu.maidroid.ui.screen.WebUiLogTag
import org.maiwithu.maidroid.webui.MaiBotWebUiSupport
import org.maiwithu.maidroid.webui.MaiBotWebViewClient

class WebUiActivity : ComponentActivity() {
    companion object {
        const val EXTRA_URL = "org.maiwithu.maidroid.extra.WEB_UI_URL"
    }

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            MaiBotWebUiSupport.enableCookies(this)
            webViewClient = MaiBotWebViewClient(logTag = WebUiLogTag)
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

        val url = intent.getStringExtra(EXTRA_URL) ?: MaiBotContainerConfig.WEB_UI_URL
        val launchUrl = if (url.isMaiBotWebUiUrl()) {
            MaiBotWebUiSupport.resolveLaunchUrl(this, url)
        } else {
            url
        }
        webView.loadUrl(launchUrl)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    private fun String.isMaiBotWebUiUrl(): Boolean {
        val normalizedUrl = trimEnd('/')
        val normalizedMaiBotUrl = MaiBotContainerConfig.WEB_UI_URL.trimEnd('/')
        return normalizedUrl == normalizedMaiBotUrl || normalizedUrl.startsWith("$normalizedMaiBotUrl/")
    }
}
