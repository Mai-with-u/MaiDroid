package org.maiwithu.maidroid.webui

import android.net.Uri
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * 加载 WebUI 用的 WebChromeClient，主页 WebUI 页签和全屏 WebUiActivity 共用：
 * 网页 console 输出转到 logcat（排查 WebUI 显示问题时用），网页要选文件时交给 WebUiFileChooser 打开系统选文件页。
 */
internal class WebUiChromeClient(
    private val logTag: String,
    private val fileChooser: WebUiFileChooser
) : WebChromeClient() {

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        Log.d(
            logTag,
            "console ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
        )
        return super.onConsoleMessage(consoleMessage)
    }

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = fileChooser.show(filePathCallback, fileChooserParams)
}
