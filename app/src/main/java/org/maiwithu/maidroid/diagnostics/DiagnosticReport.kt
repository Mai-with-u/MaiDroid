package org.maiwithu.maidroid.diagnostics

import java.io.OutputStream
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// 诊断报告的内容、排版和打包。收集（读设备信息、容器状态、日志文件）在 DiagnosticsManager 里做，
// 这里只处理已经收集好的数据，全是纯函数，方便单元测试。

/** 报告里的一节：标题加若干“名称：值”。 */
internal data class ReportSection(val title: String, val items: List<Pair<String, String>>)

/** 日志里认出的一次失败：时间（MM-dd HH:mm:ss）、错误码、说明。 */
internal data class RecordedFailure(val time: String, val code: String, val message: String)

/**
 * 一份诊断报告。failure 是用户从哪个失败点打开的反馈（从设置页、终端页打开时为空）；
 * appVersion、maiBotVersion、device 还会预填进 GitHub 的 Issue 表单。
 */
internal data class DiagnosticReport(
    val generatedAt: String,
    val failure: Failure?,
    val appVersion: String,
    val maiBotVersion: String?,
    val device: String,
    val sections: List<ReportSection>,
    val recentFailures: List<RecordedFailure>,
    val notes: List<String> = emptyList()
) {
    /** 预填 Issue 的错误码：从失败点打开的用它的码，否则用日志里最近一次失败的码。 */
    val errorCode: String? get() = failure?.code ?: recentFailures.lastOrNull()?.code
}

/** 报告正文（纯文本），诊断包里的 report.txt 和复制出去的反馈文字开头都是它。 */
internal fun formatReport(report: DiagnosticReport): String = buildString {
    appendLine("MaiDroid 诊断报告")
    appendLine("生成时间：${report.generatedAt}")
    report.failure?.let { failure ->
        appendLine()
        appendLine("错误码：${failure.code}")
        appendLine("出错的步骤：${failure.step.label}")
        appendLine("说明：${failure.message}")
        failure.cause?.let { appendLine("可能的原因：${it.title}。${it.advice}") }
    }
    for (section in report.sections) {
        appendLine()
        appendLine("【${section.title}】")
        section.items.forEach { (name, value) -> appendLine("$name：$value") }
    }
    appendLine()
    appendLine("【最近的失败】")
    if (report.recentFailures.isEmpty()) {
        appendLine("（日志里没有失败记录）")
    } else {
        report.recentFailures.forEach { appendLine("${it.time} ${it.code} ${it.message}") }
    }
    if (report.notes.isNotEmpty()) {
        appendLine()
        report.notes.forEach { appendLine(it) }
    }
}

/**
 * 从日志文件的行里挑出失败记录，按时间先后取最后 limit 条。
 * 日志文件每行开头是 TerminalLogFiles 写的 “MM-dd HH:mm:ss.SSS ”，时间只保留到秒。
 */
internal fun recentFailuresFromLog(lines: List<String>, limit: Int = 10): List<RecordedFailure> =
    lines.mapNotNull { line ->
        val (code, message) = parseFailureLogLine(line) ?: return@mapNotNull null
        val time = line.take(LOG_TIME_LENGTH).takeIf { it.matches(LOG_TIME) }.orEmpty()
        RecordedFailure(time, code, message)
    }.takeLast(limit)

private const val LOG_TIME_LENGTH = 14
private val LOG_TIME = Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}""")

/**
 * 复制到剪贴板、贴进 Issue “日志”一栏的文字：报告正文 + 最近的终端输出（已打码）。
 * GitHub 一个 Issue 正文最多 65536 个字符，表单的其他栏也要占一些，总长控制在 maxChars 以内：
 * 报告放不下就截断；日志从最后往前取整行，放不下的旧行丢掉。
 */
internal fun feedbackClipboardText(reportText: String, logLines: List<String>, maxChars: Int = 45_000): String {
    val report = reportText.trimEnd().take(maxChars)
    val header = "\n\n----- 最近的终端输出（已打码）-----\n"
    var budget = maxChars - report.length - header.length
    if (budget <= 0 || logLines.isEmpty()) return report
    val kept = ArrayDeque<String>()
    for (line in logLines.asReversed()) {
        val cost = line.length + 1
        if (cost > budget) break
        kept.addFirst(line)
        budget -= cost
    }
    return report + header + kept.joinToString("\n")
}

/** 诊断包里的一个文件。 */
internal class BundleEntry(val name: String, val content: ByteArray)

/** 把报告和各个日志文件打成 zip 写进 out（用户在“另存为”里选的位置），不关闭 out。 */
internal fun writeDiagnosticBundle(out: OutputStream, entries: List<BundleEntry>) {
    val zip = ZipOutputStream(out)
    for (entry in entries) {
        zip.putNextEntry(ZipEntry(entry.name))
        zip.write(entry.content)
        zip.closeEntry()
    }
    zip.finish()
}

/**
 * 预填好的 GitHub 缺陷报告地址。Issue 表单只能用链接参数预填文字类的栏（按栏的 id），下拉框和勾选框不行；
 * 日志不放进链接：链接有长度上限，令牌之类的东西也不该进浏览器历史，日志由用户从剪贴板粘贴。
 */
internal object FeedbackIssue {
    const val REPOSITORY_URL = "https://github.com/Mai-with-u/MaiDroid"
    private const val TEMPLATE = "bug_report.yml"
    private const val TITLE_MAX_LENGTH = 80

    fun newIssueUrl(report: DiagnosticReport): String {
        val code = report.errorCode
        val summary = report.failure?.message ?: report.recentFailures.lastOrNull()?.message
        val title = listOfNotNull("[Bug]", code, summary).joinToString(" ").take(TITLE_MAX_LENGTH)
        val description = buildString {
            if (code != null) appendLine("错误码 $code：${summary.orEmpty()}")
            appendLine()
            append("（请补充：出问题前你在做什么，和你预期的有什么不同）")
        }
        val params = listOfNotNull(
            "template" to TEMPLATE,
            "title" to title,
            "app-version" to report.appVersion,
            report.maiBotVersion?.let { "maibot-version" to it },
            "device" to report.device,
            code?.let { "error-code" to it },
            "description" to description
        )
        return "$REPOSITORY_URL/issues/new?" + params.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
    }

    // URLEncoder 按表单编码把空格写成 +，这里换成 %20，免得落到不按表单解码的地方变成加号
    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}
