package org.maiwithu.maidroid.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.diagnostics.FeedbackPackage

class FeedbackDialogTest {
    private fun pkg(terminalLogs: Int, crashes: Int, maiBotLog: Boolean) = FeedbackPackage(
        failure = null,
        reportText = "",
        clipboardText = "",
        issueUrl = "",
        entries = emptyList(),
        fileName = "maidroid-diagnostics.zip",
        terminalLogCount = terminalLogs,
        crashCount = crashes,
        includesMaiBotLog = maiBotLog
    )

    @Test
    fun contentsListOnlyWhatIsIncluded() {
        val full = feedbackContentsText(pkg(terminalLogs = 3, crashes = 1, maiBotLog = true))
        assertTrue(full.startsWith("诊断包包括：诊断报告（"))
        assertTrue(full.contains("）、终端输出 3 份、MaiBot 日志、闪退记录 1 份。"))

        val minimal = feedbackContentsText(pkg(terminalLogs = 0, crashes = 0, maiBotLog = false))
        assertEquals(
            "诊断包包括：诊断报告（版本、机型、权限、网络、容器和运行状态、最近的失败）。" +
                "令牌、密钥、QQ 号、手机号和公网 IP 已自动打码；聊天内容没有处理，发出前可以看一眼。",
            minimal
        )
    }
}
