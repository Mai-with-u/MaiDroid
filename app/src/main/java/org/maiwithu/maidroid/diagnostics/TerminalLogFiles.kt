package org.maiwithu.maidroid.diagnostics

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 把终端输出一行行写进日志文件。TerminalLog 只在内存里留最近几百行，应用被杀、闪退或重启后就没了，
 * 用户事后反馈问题时只能凭记忆描述；落到文件里，诊断包就能带上出问题那一次的完整输出。
 *
 * 每次进程启动开一个新文件 terminal-<启动时间>.log，写满 FILE_LIMIT_BYTES 换下一个分段（文件名多一个 .2、.3）；
 * 目录总大小超过 TOTAL_LIMIT_BYTES 时从最旧的文件删起，正在写的那个不删。
 * 每行前面加上手机本地时间，排查时能和 logcat、MaiBot 自己的日志对上。
 * 不是线程安全的，由 TerminalLogRecorder 在同一个协程里串行调用。
 */
internal class TerminalLogFiles(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    private val fileLimitBytes: Long = FILE_LIMIT_BYTES,
    private val totalLimitBytes: Long = TOTAL_LIMIT_BYTES
) {
    companion object {
        const val FILE_LIMIT_BYTES = 2L * 1024 * 1024
        const val TOTAL_LIMIT_BYTES = 12L * 1024 * 1024
        const val FILE_PREFIX = "terminal-"
        const val FILE_SUFFIX = ".log"

        // 写不进去（存储满了、目录被删）之后隔这么久再试一次：清出空间后日志能自己接着写
        const val RETRY_AFTER_FAILURE_MS = 60_000L

        private val sessionFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private val lineFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    }

    private var sessionName: String? = null
    private var part = 1
    private var current: File? = null
    private var writer: BufferedWriter? = null
    private var writtenBytes = 0L

    // 写失败后到这个时间之前不再尝试，免得存储满时每来一行都重试、开出一串空分段；终端输出和 logcat 照常
    private var pausedUntil = 0L

    /** 正在写的文件；还没写过任何一行时为 null。 */
    val currentFile: File? get() = current

    /** 写一行：加时间前缀 -> 当前分段写满就换下一个 -> 立刻落盘（闪退前的最后几行也不会丢）。 */
    fun append(line: String) {
        if (now() < pausedUntil) return
        val timestamp = lineFormat.withZone(zoneId).format(Instant.ofEpochMilli(now()))
        val text = "$timestamp $line\n"
        val bytes = text.toByteArray(Charsets.UTF_8).size
        if (writer == null || (writtenBytes > 0 && writtenBytes + bytes > fileLimitBytes)) {
            openNextFile()
        }
        val out = writer ?: return
        runCatching {
            out.write(text)
            out.flush()
        }.onFailure {
            // 写不进去：关掉这个文件，过一会儿再开下一个分段重试
            close()
            pausedUntil = now() + RETRY_AFTER_FAILURE_MS
            return
        }
        writtenBytes += bytes
    }

    fun close() {
        runCatching { writer?.close() }
        writer = null
    }

    /** 日志目录里的全部终端日志，最新的在前。 */
    fun recentFiles(): List<File> = listLogFiles(dir).sortedByDescending { it.lastModified() }

    // 换到下一个文件：第一次写时按启动时间起名，之后同一次启动的分段依次加序号；换完顺手删掉超出总量的旧文件。
    private fun openNextFile() {
        close()
        dir.mkdirs()
        val session = sessionName ?: sessionFormat.withZone(zoneId).format(Instant.ofEpochMilli(now())).also {
            sessionName = it
        }
        val name = if (part == 1) "$FILE_PREFIX$session$FILE_SUFFIX" else "$FILE_PREFIX$session.$part$FILE_SUFFIX"
        part++
        val file = File(dir, name)
        current = file
        writtenBytes = if (file.isFile) file.length() else 0L
        writer = runCatching { FileOutputStream(file, true).bufferedWriter() }.getOrNull()
        // 打不开（存储满了、目录被删）就先停一会儿再试
        if (writer == null) pausedUntil = now() + RETRY_AFTER_FAILURE_MS
        pruneOldFiles()
    }

    private fun pruneOldFiles() {
        val active = current?.name
        val entries = listLogFiles(dir).map { LogFileEntry(it.name, it.length(), it.lastModified()) }
        logFilesToPrune(entries, active, totalLimitBytes).forEach { File(dir, it).delete() }
    }
}

/** 日志目录里的一个文件：名字、大小、最后修改时间。 */
internal data class LogFileEntry(val name: String, val sizeBytes: Long, val modifiedAt: Long)

/**
 * 总大小超过 limitBytes 时要删的文件名：从最旧的删起，删到不超限为止；activeName（正在写的）永远保留。
 * 只剩正在写的那个时即使超限也不删。
 */
internal fun logFilesToPrune(files: List<LogFileEntry>, activeName: String?, limitBytes: Long): List<String> {
    var total = files.sumOf { it.sizeBytes }
    if (total <= limitBytes) return emptyList()
    val pruned = mutableListOf<String>()
    for (entry in files.sortedBy { it.modifiedAt }) {
        if (total <= limitBytes) break
        if (entry.name == activeName) continue
        pruned += entry.name
        total -= entry.sizeBytes
    }
    return pruned
}

/** 读文件最后 maxBytes 字节的文本；截掉的开头那一行不完整，直接丢掉。 */
internal fun readTail(file: File, maxBytes: Long): String {
    if (!file.isFile) return ""
    val length = file.length()
    if (length <= maxBytes) return file.readText(Charsets.UTF_8)
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(length - maxBytes)
        val buffer = ByteArray(maxBytes.toInt())
        raf.readFully(buffer)
        val text = String(buffer, Charsets.UTF_8)
        return text.substringAfter('\n', text)
    }
}

private fun listLogFiles(dir: File): List<File> =
    dir.listFiles { file ->
        file.isFile && file.name.startsWith(TerminalLogFiles.FILE_PREFIX) && file.name.endsWith(TerminalLogFiles.FILE_SUFFIX)
    }?.toList().orEmpty()
