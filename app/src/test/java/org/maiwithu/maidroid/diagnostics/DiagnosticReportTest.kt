package org.maiwithu.maidroid.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

class DiagnosticReportTest {
    private val failure = Failure(FailureStep.UvSync, "安装 Python 依赖包失败：dns error", FailureCause.Dns)

    private fun report(failure: Failure? = this.failure, recent: List<RecordedFailure> = emptyList()) =
        DiagnosticReport(
            generatedAt = "2026-11-01 12:30:00 +08:00",
            failure = failure,
            appVersion = "0.2.0",
            maiBotVersion = "1.3.19",
            device = "HONOR OXF-AN10 / Android 12",
            sections = listOf(ReportSection("应用", listOf("MaiDroid" to "0.2.0（5，release）"))),
            recentFailures = recent
        )

    @Test
    fun reportTextStartsWithCodeAndCause() {
        val text = formatReport(report())
        assertTrue(text.contains("错误码：UV-SYNC/NET-DNS"))
        assertTrue(text.contains("出错的步骤：安装 Python 依赖包"))
        assertTrue(text.contains("可能的原因：域名解析失败。"))
        assertTrue(text.contains("【应用】\nMaiDroid：0.2.0（5，release）"))
        assertTrue(text.contains("（日志里没有失败记录）"))
    }

    @Test
    fun recentFailuresAreReadFromLogLines() {
        val lines = listOf(
            "11-01 12:00:00.001 [MaiBot] 普通输出",
            "11-01 12:01:02.003 [MaiDroid] 错误码 MB-CLONE/NET-DNS 克隆失败",
            "11-01 12:05:00.000 [MaiDroid] 错误码 UV-SYNC 安装依赖失败"
        )
        assertEquals(
            listOf(
                RecordedFailure("11-01 12:01:02", "MB-CLONE/NET-DNS", "克隆失败"),
                RecordedFailure("11-01 12:05:00", "UV-SYNC", "安装依赖失败")
            ),
            recentFailuresFromLog(lines)
        )
        assertEquals(1, recentFailuresFromLog(lines, limit = 1).size)
        // 没有从失败点打开时，预填的错误码取最近一次失败
        assertEquals("UV-SYNC", report(failure = null, recent = recentFailuresFromLog(lines)).errorCode)
    }

    @Test
    fun clipboardTextKeepsNewestLogLinesWithinLimit() {
        val logs = (1..100).map { "line $it" }
        val text = feedbackClipboardText("报告", logs, maxChars = 80)
        assertTrue(text.length <= 80)
        assertTrue(text.startsWith("报告"))
        assertTrue(text.endsWith("line 100"))
        assertFalse(text.contains("line 1\n"))
        assertEquals("报告", feedbackClipboardText("报告\n\n", emptyList()))
    }

    @Test
    fun bundleContainsAllEntries() {
        val out = ByteArrayOutputStream()
        writeDiagnosticBundle(
            out,
            listOf(BundleEntry("report.txt", "报告".toByteArray()), BundleEntry("logs/a.log", "x".toByteArray()))
        )
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { names += it.name }
        }
        assertEquals(listOf("report.txt", "logs/a.log"), names)
    }

    @Test
    fun issueUrlPrefillsTextFields() {
        val url = FeedbackIssue.newIssueUrl(report())
        assertTrue(url.startsWith("https://github.com/Mai-with-u/MaiDroid/issues/new?template=bug_report.yml&"))
        assertFalse("空格不能编码成加号", url.contains("+"))
        val params = url.substringAfter('?').split('&').associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals("[Bug] UV-SYNC/NET-DNS 安装 Python 依赖包失败：dns error", params["title"])
        assertEquals("0.2.0", params["app-version"])
        assertEquals("1.3.19", params["maibot-version"])
        assertEquals("HONOR OXF-AN10 / Android 12", params["device"])
        assertEquals("UV-SYNC/NET-DNS", params["error-code"])
        assertTrue(params.getValue("description").startsWith("错误码 UV-SYNC/NET-DNS：安装 Python 依赖包失败"))
    }
}
