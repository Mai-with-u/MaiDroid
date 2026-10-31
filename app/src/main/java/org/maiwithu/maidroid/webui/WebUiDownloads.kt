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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网页里的下载（WebUI 的“导出”“下载”按钮），主页 WebUI 页签和全屏 WebUiActivity 各持有一个，由 createWebUiWebView 接到 WebView 上。
 * WebView 自己不保存下载：没设 DownloadListener 时，下载请求发出后马上被取消，网页点了导出什么也不发生
 * （真机实测：MaiBot 数据迁移的压缩包已经在容器里生成好，下载接口也被请求过，文件就是到不了用户手里）。
 *
 * 两条路，最后都弹系统“另存为”（ACTION_CREATE_DOCUMENT）让用户选位置：
 * - http 地址（数据迁移导出的 zip）：onDownloadStart -> 另存为 -> 带上 WebView 的登录 Cookie 重新请求，边下边写进选好的文件；
 * - blob:、data: 地址（主题、设置、日志、图片等网页现场生成的文件）：注入脚本（WebUiDownloadScripts）在点击时读出内容，
 *   经 JS 桥分块送来，WebUiBlobReceiver 先存成缓存文件 -> 另存为 -> 复制过去后删掉缓存。
 * 用“另存为”而不是直接写进“下载”目录：minSdk 26，Android 8、9 写公共目录要存储权限；另存为不用权限，还能直接存进网盘、QQ 等应用。
 *
 * launcher 由宿主在注册 ActivityResult 后赋值；宿主销毁 WebView 前调用 release。
 * JS 桥对 WebView 里打开的任何页面都可见，但它能做的只是弹一个要用户确认的“另存为”，和普通浏览器允许网站下载文件一样。
 */
internal class WebUiDownloads(context: Context, private val logTag: String) : DownloadListener {
    var launcher: ActivityResultLauncher<Intent>? = null

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // 写文件不跟页面一起取消：用户点完保存马上关掉全屏 WebUI 时，大文件还要接着写完。
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val blobReceiver = WebUiBlobReceiver(File(appContext.cacheDir, TEMP_DIRECTORY))

    // 以下状态只在主线程读写。
    private var webView: WebView? = null
    private var pendingSave: PendingSave? = null
    private var released = false

    /** 等用户在“另存为”里选位置的下载。 */
    private sealed interface PendingSave {
        val fileName: String
        val mimeType: String

        /** 网页上的 http 地址，选好位置后重新请求。 */
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
    }

    init {
        scope.launch { blobReceiver.deleteStaleFiles() }
    }

    /** 接到 WebView 上：注册下载回调和 JS 桥。要在 loadUrl 之前调用，JS 桥只对之后加载的页面生效。 */
    fun attachTo(webView: WebView) {
        this.webView = webView
        webView.setDownloadListener(this)
        webView.addJavascriptInterface(Bridge(), WebUiDownloadScripts.BRIDGE_NAME)
    }

    /**
     * WebView 遇到下载时在主线程回调：http 地址直接等用户选位置；
     * blob:、data: 地址说明没经过注入脚本的点击拦截，补跑一次脚本让网页把内容送过来。
     */
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

        // 要重新下载的大文件先提示一声；网页里收来的小文件复制一下就完，只报结果，免得两条提示排队。
        if (save is PendingSave.Remote) toast("正在保存 ${save.fileName}")
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

    /** 宿主销毁 WebView 前调用：丢掉还在等位置的下载和正在接收的文件；已经开始写的保存会继续写完。 */
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

    private fun write(save: PendingSave, target: Uri) {
        val output = appContext.contentResolver.openOutputStream(target, "w")
            ?: throw IOException("打不开选好的文件")
        output.use { stream ->
            when (save) {
                is PendingSave.Remote -> downloadTo(save, stream)
                is PendingSave.Local -> save.file.inputStream().use { it.copyTo(stream, COPY_BUFFER_BYTES) }
            }
        }
    }

    // 带上 WebView 的 Cookie（MaiBot WebUI 的登录态 maibot_session 在 Cookie 里）重新请求，边下边写，大文件不进内存。
    // WebView 自己发出的那次请求在回调前已经被取消了；MaiBot 的导出包 24 小时内可以反复下载。
    private fun downloadTo(save: PendingSave.Remote, output: OutputStream) {
        val connection = URL(save.url).openConnection() as HttpURLConnection
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
        if (save is PendingSave.Local) save.file.delete()
    }

    private fun toast(message: String) {
        mainHandler.post { Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show() }
    }

    /**
     * 给注入脚本用的 JS 桥（window.MaiDroidDownloads）。方法在 WebView 的 JavaBridge 线程上调用，
     * 收文件交给 WebUiBlobReceiver，收完转回主线程弹“另存为”。
     */
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
