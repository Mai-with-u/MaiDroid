package org.maiwithu.maidroid.webui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.runtime.openDirectOrDefault
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.URL
import java.util.UUID

/**
 * 网页里的下载（WebUI 的“导出”“下载”按钮），主页 WebUI 页签和全屏 WebUiActivity 各持有一个。
 *
 * 两条路，最后都弹系统“另存为”（ACTION_CREATE_DOCUMENT）让用户选位置：
 * - GeckoView 外部响应（数据迁移导出的 zip 等 http 下载）：ContentDelegate.onExternalResponse(WebResponse) ->
 *   一边立即在后台线程把 response.body 写进临时文件，一边在主线程立刻弹“另存为”，选完位置后等临时文件写完再复制到用户选的 Uri，
 *   最后删除临时文件；
 * - blob:、data: 地址（主题、设置、日志、图片等网页现场生成的文件）：内置 WebExtension 在页面拦截点击并经 Native Messaging
 *   分块送来 -> WebUiBlobReceiver 先存成缓存文件 -> 另存为 -> 复制过去后删掉缓存。
 * 用“另存为”而不是直接写进“下载”目录：minSdk 26，Android 8、9 写公共目录要存储权限；另存为不用权限，还能直接存进网盘、QQ 等应用。
 */
internal class WebUiDownloads(context: Context, private val logTag: String) : DownloadListener {
    var launcher: ActivityResultLauncher<Intent>? = null

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // 写文件不跟页面一起取消：用户点完保存马上关掉全屏 WebUI 时，大文件还要接着写完。
    private val scope = backgroundScope("WebUI 下载", Dispatchers.IO)
    private val tempDirectory = File(appContext.cacheDir, TEMP_DIRECTORY)
    private val blobReceiver = WebUiBlobReceiver(tempDirectory)

    // 以下状态只在主线程读写。
    private var webView: WebView? = null
    private var pendingSave: PendingSave? = null
    private var released = false

    /** 是否仍有尚未选定保存位置的下载任务（退后台休眠前用于防误销毁）。 */
    val hasPending: Boolean get() = pendingSave != null

    /** 等用户在“另存为”里选位置的下载。 */
    private sealed interface PendingSave {
        val fileName: String
        val mimeType: String

        /** 旧 WebView 过渡用：网页上的 http 地址，选好位置后重新请求。 */
        class Remote(
            val url: String,
            val userAgent: String?,
            override val fileName: String,
            override val mimeType: String
        ) : PendingSave

        /** 已经从网页收进缓存目录的文件，选好位置后复制过去。 */
        class Local(
            val file: File,
            override val fileName: String,
            override val mimeType: String
        ) : PendingSave

        /** GeckoView 的 HTTP 下载响应流：后台先把 WebResponse.body 缓存到临时文件，与此同时前台已经弹出“另存为”。 */
        class DeferredLocal(
            val tempFile: File,
            val fileDeferred: CompletableDeferred<File>,
            val downloadJob: Job,
            override val fileName: String,
            override val mimeType: String
        ) : PendingSave
    }

    init {
        scope.launch { blobReceiver.deleteStaleFiles() }
    }

    /** 旧 WebView 过渡用：接到 WebView 上。 */
    fun attachTo(webView: WebView) {
        this.webView = webView
        webView.setDownloadListener(this)
        webView.addJavascriptInterface(Bridge(), WebUiDownloadScripts.BRIDGE_NAME)
    }

    /** 旧 WebView 过渡用：仅摘除当前持有的 WebView 引用。 */
    fun detachFrom(target: WebView) {
        if (webView === target) {
            webView = null
        }
    }

    /**
     * GeckoView 收到外部响应下载回调（HTTP 文件导出等）：
     * 不能等用户选完“另存为”才读 WebResponse.body，否则响应流可能超时或随页面跳转失效；
     * 因此立刻在后台 IO 协程把响应流写入临时缓存文件，并同步在主线程弹出系统“另存为”。
     */
    fun onExternalResponse(response: WebResponse) {
        val bodyStream = response.body
        if (bodyStream == null) {
            Log.w(logTag, "External response has null body: ${response.uri}")
            toast("保存失败：下载内容为空")
            return
        }

        val contentDisposition = headerIgnoreCase(response.headers, "Content-Disposition")
        val contentType = headerIgnoreCase(response.headers, "Content-Type")
        val fileName = downloadFileName(null, response.uri, contentDisposition, contentType)
        val targetMime = saveMimeType(fileName, contentType)

        val fileDeferred = CompletableDeferred<File>()
        val tempFile = File(tempDirectory, "${UUID.randomUUID()}.dl")
        val job = scope.launch {
            try {
                tempDirectory.mkdirs()
                bodyStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output, COPY_BUFFER_BYTES)
                    }
                }
                fileDeferred.complete(tempFile)
            } catch (error: Throwable) {
                runCatching { bodyStream.close() }
                tempFile.delete()
                fileDeferred.completeExceptionally(error)
            }
        }
        // 请求保存可能来自 GeckoView 工作线程或主线程，这里统一交回主线程持有 pendingSave 并弹出选择器。
        val save = PendingSave.DeferredLocal(tempFile, fileDeferred, job, fileName, targetMime)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            requestSave(save)
        } else {
            mainHandler.post { requestSave(save) }
        }
    }

    /** 处理来自内置 WebExtension 的 blob/data 分块下载开始消息。 */
    fun handleBlobBegin(fileName: String, mimeType: String, sizeBytes: Long): GeckoResult<Any> {
        val result = GeckoResult<Any>()
        scope.launch {
            val name = downloadFileName(fileName, "", null, mimeType)
            val id = blobReceiver.begin(name, saveMimeType(name, mimeType), sizeBytes)
            if (id == null) {
                Log.w(logTag, "Refused inline download of $sizeBytes bytes")
                toast("保存失败：文件太大或手机空间不足")
            }
            result.complete(JSONObject(WebUiBridgeProtocol.blobBeginReplyJsonText(id)))
        }
        return result
    }

    /** 处理来自内置 WebExtension 的 blob/data 分块追加消息。 */
    fun handleBlobAppend(id: String, base64: String): GeckoResult<Any> {
        val result = GeckoResult<Any>()
        scope.launch {
            val ok = blobReceiver.append(id, base64)
            result.complete(JSONObject(WebUiBridgeProtocol.blobStatusReplyJsonText(ok)))
        }
        return result
    }

    /** 处理来自内置 WebExtension 的 blob/data 传输完成消息：校验后切主线程弹“另存为”。 */
    fun handleBlobFinish(id: String): GeckoResult<Any> {
        val result = GeckoResult<Any>()
        scope.launch {
            val received = blobReceiver.finish(id)
            mainHandler.post {
                if (received == null) {
                    toast("保存失败：网页里的文件没有收完整")
                } else {
                    requestSave(PendingSave.Local(received.file, received.fileName, received.mimeType))
                }
            }
            result.complete(JSONObject(WebUiBridgeProtocol.blobStatusReplyJsonText(received != null)))
        }
        return result
    }

    /** 处理来自内置 WebExtension 的 blob/data 读取失败通知。 */
    fun handleBlobFail(id: String, message: String?): GeckoResult<Any> {
        val result = GeckoResult<Any>()
        scope.launch {
            blobReceiver.abort(id)
            Log.w(logTag, "Page failed to read download: $message")
            toast("保存失败：读不到网页里的文件")
            result.complete(JSONObject(WebUiBridgeProtocol.blobStatusReplyJsonText(true)))
        }
        return result
    }

    /** 旧 WebView 过渡用下载回调。 */
    override fun onDownloadStart(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        contentLength: Long
    ) {
        val fileName = downloadFileName(null, url, contentDisposition, mimetype)
        when (url.substringBefore(':').lowercase()) {
            "http", "https" -> requestSave(
                PendingSave.Remote(url, userAgent, fileName, saveMimeType(fileName, mimetype))
            )
            "blob", "data" -> webView?.evaluateJavascript(WebUiDownloadScripts.saveUrlScript(url, fileName), null)
            else -> {
                Log.w(logTag, "Unsupported download scheme: ${url.substringBefore(':')}")
                toast("保存失败：不支持这种下载地址")
            }
        }
    }

    /** 宿主收到“另存为”的结果后调用：选好了就在后台写文件，取消了就丢掉这次下载。 */
    fun onResult(resultCode: Int, data: Intent?) {
        val target = data?.data?.takeIf { resultCode == Activity.RESULT_OK }
        val save = pendingSave
        pendingSave = null
        if (save == null) {
            // 选位置期间界面被重建（转屏等），待保存的内容跟着旧界面没了：删掉“另存为”刚建好的空文件。
            target?.let(::deleteDocument)
            return
        }
        if (target == null) {
            discard(save)
            return
        }

        // 要等待网络或大文件流落地时先提示一声；内存收完的小文件复制一下就完，只报结果，免得两条提示排队。
        if (save is PendingSave.Remote || (save is PendingSave.DeferredLocal && !save.fileDeferred.isCompleted)) {
            toast("正在保存 ${save.fileName}")
        }
        scope.launch {
            try {
                write(save, target)
                toast("已保存 ${displayName(target) ?: save.fileName}")
            } catch (error: Exception) {
                Log.w(logTag, "Saving download failed: ${error.message}", error)
                deleteDocument(target)
                toast("保存失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                discard(save)
            }
        }
    }

    /** 宿主销毁窗口前调用：丢掉还在等位置的下载和正在接收的文件；已经开始写的保存会继续写完。 */
    fun release() {
        released = true
        launcher = null
        pendingSave?.let(::discard)
        pendingSave = null
        blobReceiver.abortCurrent()
        webView = null
    }

    // 弹“另存为”。上一个还在等位置的下载先丢掉：系统一次只弹一个选择页。
    private fun requestSave(save: PendingSave) {
        if (released) {
            discard(save)
            return
        }
        pendingSave?.let(::discard)
        pendingSave = null

        val currentLauncher = launcher
        if (currentLauncher == null) {
            discard(save)
            toast("保存失败：页面还没准备好，请再点一次")
            return
        }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = save.mimeType
            putExtra(Intent.EXTRA_TITLE, save.fileName)
        }
        pendingSave = save
        try {
            currentLauncher.launch(intent)
        } catch (error: ActivityNotFoundException) {
            // 精简过的系统可能没有文档界面，没法选位置。
            Log.w(logTag, "No activity can create documents: ${error.message}")
            pendingSave = null
            discard(save)
            toast("保存失败：系统里没有可以选择保存位置的应用")
        }
    }

    private suspend fun write(save: PendingSave, target: Uri) {
        val localFile = if (save is PendingSave.DeferredLocal) save.fileDeferred.await() else null
        val output = appContext.contentResolver.openOutputStream(target, "w")
            ?: throw IOException("打不开选好的文件")
        output.use { stream ->
            when (save) {
                is PendingSave.Remote -> downloadTo(save, stream)
                is PendingSave.Local -> save.file.inputStream().use { it.copyTo(stream, COPY_BUFFER_BYTES) }
                is PendingSave.DeferredLocal -> localFile!!.inputStream().use { it.copyTo(stream, COPY_BUFFER_BYTES) }
            }
        }
    }

    // 旧 WebView 过渡用：带上 Cookie 重新请求远程文件。
    private fun downloadTo(save: PendingSave.Remote, output: OutputStream) {
        val connection = openDirectOrDefault(URL(save.url))
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            save.userAgent?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("User-Agent", it) }
            CookieManager.getInstance().getCookie(save.url)
                ?.takeIf { it.isNotBlank() }
                ?.let { connection.setRequestProperty("Cookie", it) }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) throw IOException("服务器返回 HTTP $responseCode")
            connection.inputStream.use { it.copyTo(output, COPY_BUFFER_BYTES) }
        } finally {
            connection.disconnect()
        }
    }

    private fun headerIgnoreCase(headers: Map<String, String>, key: String): String? =
        headers.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    // 用户可能在“另存为”里改了名字，提示里用实际存下的名字。
    private fun displayName(uri: Uri): String? =
        runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()

    private fun deleteDocument(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
            .onFailure { Log.w(logTag, "Failed to delete unfinished download: ${it.message}") }
    }

    private fun discard(save: PendingSave) {
        when (save) {
            is PendingSave.Local -> save.file.delete()
            is PendingSave.DeferredLocal -> {
                save.downloadJob.cancel()
                // 等后台下载协程彻底停下（含正在关闭的文件流）再删临时文件，避免 Windows/Android 上句柄未释放或遗漏。
                save.downloadJob.invokeOnCompletion { save.tempFile.delete() }
            }
            is PendingSave.Remote -> Unit
        }
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }

    /** 旧 WebView 过渡用 JS 桥。 */
    private inner class Bridge {
        @JavascriptInterface
        fun begin(fileName: String?, mimeType: String?, sizeBytes: Long): String {
            val name = downloadFileName(fileName, "", null, mimeType)
            val id = blobReceiver.begin(name, saveMimeType(name, mimeType), sizeBytes)
            if (id == null) {
                Log.w(logTag, "Refused inline download of $sizeBytes bytes")
                toast("保存失败：文件太大或手机空间不足")
            }
            return id.orEmpty()
        }

        @JavascriptInterface
        fun append(id: String, base64: String): Boolean = blobReceiver.append(id, base64)

        @JavascriptInterface
        fun finish(id: String) {
            val received = blobReceiver.finish(id)
            mainHandler.post {
                if (received == null) {
                    toast("保存失败：网页里的文件没有收完整")
                } else {
                    requestSave(PendingSave.Local(received.file, received.fileName, received.mimeType))
                }
            }
        }

        @JavascriptInterface
        fun fail(id: String, message: String?) {
            blobReceiver.abort(id)
            Log.w(logTag, "Page failed to read download: $message")
            toast("保存失败：读不到网页里的文件")
        }
    }

    private companion object {
        const val TEMP_DIRECTORY = "webui-downloads"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}
