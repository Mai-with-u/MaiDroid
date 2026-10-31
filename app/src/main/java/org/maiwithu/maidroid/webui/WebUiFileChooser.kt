package org.maiwithu.maidroid.webui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import androidx.activity.result.ActivityResultLauncher

/**
 * 网页里 `<input type="file">` 的选文件流程，主页 WebUI 页签和全屏 WebUiActivity 各持有一个。
 * 网页点“发送图片”等按钮 -> WebUiChromeClient.onShowFileChooser 交到 show -> 用宿主注册好的 launcher 打开系统选文件页 ->
 * 选完或取消后宿主把结果交给 onResult -> 把选中的 content:// 地址回传网页。
 * 原因：WebView 默认不处理文件选择，不实现时网页的上传按钮点了没有任何反应（issue #3：本地聊天室发不了图片）。
 *
 * launcher 由宿主在注册 ActivityResult 后赋值；还没注册好时按“取消”回复网页。
 */
internal class WebUiFileChooser(private val logTag: String) {
    var launcher: ActivityResultLauncher<Intent>? = null

    private var pendingCallback: ValueCallback<Array<Uri>>? = null

    /**
     * 打开系统选文件页。返回 true 表示已经接手（无论成功与否都会且只会回调一次 callback）。
     * 上一次的回调还没回复时先按取消结束它：WebView 在收到回复前不会再弹新的选择器。
     */
    fun show(callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
        cancelPending()

        val currentLauncher = launcher
        if (currentLauncher == null) {
            callback.onReceiveValue(null)
            return true
        }

        val allowMultiple = params.mode == FileChooserParams.MODE_OPEN_MULTIPLE
        val intent = fileChooserIntent(params.acceptTypes.orEmpty().toList(), allowMultiple)
        pendingCallback = callback
        try {
            currentLauncher.launch(intent)
        } catch (error: ActivityNotFoundException) {
            // 系统里没有能选文件的应用（极少见的精简 ROM），直接按取消回复，网页可以再点一次。
            Log.w(logTag, "No activity can pick files: ${error.message}")
            pendingCallback = null
            callback.onReceiveValue(null)
        }
        return true
    }

    /** 宿主收到选文件页的结果后调用：取消或没选时回 null，网页会当作用户取消。 */
    fun onResult(resultCode: Int, data: Intent?) {
        val callback = pendingCallback ?: return
        pendingCallback = null
        val uris = if (resultCode == Activity.RESULT_OK) selectedUris(data) else emptyList()
        callback.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    /** 宿主销毁 WebView 前调用，把还没回复的选择按取消结束。 */
    fun cancelPending() {
        pendingCallback?.onReceiveValue(null)
        pendingCallback = null
    }

    // 多选时结果在 ClipData 里，单选时在 data 里；两处都看，去重后按选择顺序返回。
    private fun selectedUris(data: Intent?): List<Uri> {
        if (data == null) return emptyList()
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clipData ->
            for (index in 0 until clipData.itemCount) {
                clipData.getItemAt(index).uri?.let(uris::add)
            }
        }
        data.data?.let(uris::add)
        return uris.distinct()
    }

    private fun fileChooserIntent(acceptTypes: List<String>, allowMultiple: Boolean): Intent {
        val mimeTypes = fileChooserMimeTypes(acceptTypes)
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // ACTION_GET_CONTENT 只认一个 type；多种类型时 type 用通配，具体类型放进 EXTRA_MIME_TYPES。
            type = mimeTypes.singleOrNull() ?: "*/*"
            if (mimeTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
            }
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, allowMultiple)
        }
    }
}

/**
 * 把网页 accept 属性拆出的类型换成选文件页能用的 MIME 类型。
 * 只保留带斜杠的 MIME 写法（image/png，或子类型写星号的通配），.png 这类扩展名写法丢掉（选文件页不认扩展名）；
 * 什么都不剩时返回空表，由调用方退回“任意文件”。WebView 有时把逗号分隔的整串 accept 放在同一项里，所以先按逗号再拆一次。
 * 注意：这里的注释不要写“斜杠紧跟星号”，Kotlin 的块注释可以嵌套，会被当成新注释的开头。
 */
internal fun fileChooserMimeTypes(acceptTypes: List<String>): List<String> =
    acceptTypes
        .flatMap { it.split(',') }
        .map { it.trim().lowercase() }
        .filter { it.contains('/') && !it.startsWith('/') && !it.endsWith('/') }
        .distinct()
