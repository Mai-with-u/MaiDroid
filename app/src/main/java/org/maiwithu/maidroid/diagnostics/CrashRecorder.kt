package org.maiwithu.maidroid.diagnostics

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 记下 MaiDroid 自己的闪退：进程里任何线程抛出没人接的异常时，先把堆栈、版本、设备和闪退前的最后几十行终端输出
 * 写进 filesDir/diagnostics/crashes/crash-<时间>.txt，再交还给系统原来的处理（照常弹“应用已停止”、结束进程）。
 * 下次打开时主界面问用户要不要反馈（pendingCrash），诊断包会带上这些文件。
 *
 * 由 MaiDroidApplication 在创建完依赖后立即 install；写文件全在闪退的那个线程里同步完成，进程随后就会被结束。
 */
internal class CrashRecorder(
    private val dir: File,
    private val header: () -> String,
    private val recentLines: () -> List<String>,
    private val now: () -> Long = System::currentTimeMillis,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    private companion object {
        const val FILE_PREFIX = "crash-"
        const val FILE_SUFFIX = ".txt"
        const val ACKNOWLEDGED_FILE = ".acknowledged"
        const val MAX_FILES = 10
        const val RECENT_LINE_COUNT = 80

        val fileNameFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")
    }

    /** 接管默认的未捕获异常处理：先记下来，再交给原来的处理器（系统的闪退流程）。 */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    /** 写一份闪退记录并删掉超出 MAX_FILES 的旧记录，返回写好的文件。 */
    fun record(threadName: String, error: Throwable): File {
        dir.mkdirs()
        val instant = Instant.ofEpochMilli(now())
        val file = File(dir, "$FILE_PREFIX${fileNameFormat.withZone(zoneId).format(instant)}$FILE_SUFFIX")
        // printStackTrace 按系统换行符写，统一成 \n，和日志文件一致（在电脑上跑单元测试时是 \r\n）
        val stackTrace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().replace("\r\n", "\n")
        val recent = runCatching { recentLines().takeLast(RECENT_LINE_COUNT) }.getOrDefault(emptyList())
        file.writeText(
            buildString {
                appendLine(runCatching(header).getOrDefault("MaiDroid"))
                appendLine("时间：${timeFormat.withZone(zoneId).format(instant)}")
                appendLine("线程：$threadName")
                appendLine()
                append(stackTrace)
                if (recent.isNotEmpty()) {
                    appendLine()
                    appendLine("----- 闪退前的终端输出 -----")
                    recent.forEach(::appendLine)
                }
            }
        )
        crashFiles().drop(MAX_FILES).forEach { it.delete() }
        return file
    }

    /** 用户还没看过的最近一次闪退；没有返回 null。 */
    fun pendingCrash(): CrashRecord? {
        val newest = crashFiles().firstOrNull() ?: return null
        val acknowledged = runCatching { File(dir, ACKNOWLEDGED_FILE).readText().trim() }.getOrNull()
        if (acknowledged != null && newest.name <= acknowledged) return null
        return CrashRecord(newest.name, summaryOf(newest))
    }

    /** 用户看过这次闪退的提示（反馈了或点了知道了），之后不再问。 */
    fun acknowledge(record: CrashRecord) {
        runCatching {
            dir.mkdirs()
            File(dir, ACKNOWLEDGED_FILE).writeText(record.fileName)
        }
    }

    // 新的在前；文件名里的时间可以直接按字符串比大小
    private fun crashFiles(): List<File> =
        dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) && file.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.name }
            .orEmpty()

    // 异常那一行（堆栈前面第一条不以空白开头、不是表头的行），给提示弹窗和错误说明用
    private fun summaryOf(file: File): String =
        runCatching {
            file.readLines()
                .drop(3)
                .firstOrNull { it.isNotBlank() && !it.first().isWhitespace() }
                ?.take(200)
        }.getOrNull() ?: "未知异常"
}

/** 一次闪退：记录文件名，和异常那一行（例如 java.lang.IllegalStateException: xxx）。 */
internal data class CrashRecord(val fileName: String, val summary: String)
