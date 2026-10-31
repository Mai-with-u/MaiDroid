package org.maiwithu.maidroid.webui

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.UUID

/**
 * 接收注入脚本经 JS 桥送来的网页文件（blob:、data: 地址的内容），先写成缓存目录里的临时文件，由 WebUiDownloads 持有。
 * 脚本 begin 报上文件名、类型和字节数 -> 一块块 append（base64）-> finish 时核对字节数，交出完整的临时文件。
 * 原因：这类文件只在网页内存里，宿主拿不到地址背后的内容，只能让网页自己读出来送过来；JS 桥只能传字符串，所以用 base64 分块。
 *
 * 同一时间只收一个文件，新的 begin 会丢掉还没收完的上一个。JS 桥的方法都在 WebView 的 JavaBridge 线程上调用，
 * 宿主线程也会来清理，所以各方法加锁。
 */
internal class WebUiBlobReceiver(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES
) {
    /** 收完整的文件；保存完或用户取消后由调用方删掉。 */
    class Received(val file: File, val fileName: String, val mimeType: String)

    private class Transfer(
        val id: String,
        val file: File,
        val output: OutputStream,
        val fileName: String,
        val mimeType: String,
        val expectedBytes: Long
    ) {
        var writtenBytes = 0L
    }

    private var current: Transfer? = null

    /** 开始接收一个文件，返回这次传输的编号；太大或建不了临时文件时返回 null，脚本据此停下。 */
    @Synchronized
    fun begin(fileName: String, mimeType: String, sizeBytes: Long): String? {
        abortCurrent()
        if (sizeBytes !in 0..maxBytes) return null
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id$PART_SUFFIX")
        val output = try {
            directory.mkdirs()
            file.outputStream().buffered()
        } catch (error: IOException) {
            return null
        }
        current = Transfer(id, file, output, fileName, mimeType, sizeBytes)
        return id
    }

    /** 追加一块 base64 数据。编号不对、数据坏了、超出 begin 报的大小或写盘失败时丢掉整个文件并返回 false。 */
    @Synchronized
    fun append(id: String, base64: String): Boolean {
        val transfer = current?.takeIf { it.id == id } ?: return false
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (error: IllegalArgumentException) {
            abortCurrent()
            return false
        }
        if (transfer.writtenBytes + bytes.size > transfer.expectedBytes) {
            abortCurrent()
            return false
        }
        try {
            transfer.output.write(bytes)
        } catch (error: IOException) {
            abortCurrent()
            return false
        }
        transfer.writtenBytes += bytes.size
        return true
    }

    /** 收尾：字节数和 begin 报的一致才交出文件，否则删掉并返回 null。 */
    @Synchronized
    fun finish(id: String): Received? {
        val transfer = current?.takeIf { it.id == id } ?: return null
        current = null
        val closed = runCatching { transfer.output.close() }.isSuccess
        if (!closed || transfer.writtenBytes != transfer.expectedBytes) {
            transfer.file.delete()
            return null
        }
        return Received(transfer.file, transfer.fileName, transfer.mimeType)
    }

    /** 网页读文件出错时调用：编号对得上就丢掉正在收的文件。 */
    @Synchronized
    fun abort(id: String) {
        if (current?.id == id) abortCurrent()
    }

    /** 丢掉正在收的文件（宿主销毁 WebView 时也调用）。 */
    @Synchronized
    fun abortCurrent() {
        val transfer = current ?: return
        current = null
        runCatching { transfer.output.close() }
        transfer.file.delete()
    }

    /**
     * 清掉以前遗留的临时文件：应用在接收或保存途中被杀、选位置时界面被重建，都会留下文件。
     * 只清一小时以前的，主页页签和全屏 WebUI 可能同时开着，不能删掉另一个正在收或等着保存的文件。
     */
    fun deleteStaleFiles(nowMillis: Long = System.currentTimeMillis()) {
        directory.listFiles()
            ?.filter { it.isFile && nowMillis - it.lastModified() > STALE_AFTER_MILLIS }
            ?.forEach { it.delete() }
    }

    companion object {
        /** 网页现场生成的文件（主题、设置、日志、图片）都很小，上限只是防止异常页面写爆缓存。 */
        const val DEFAULT_MAX_BYTES = 512L * 1024 * 1024

        private const val PART_SUFFIX = ".part"
        private const val STALE_AFTER_MILLIS = 60L * 60 * 1000
    }
}
