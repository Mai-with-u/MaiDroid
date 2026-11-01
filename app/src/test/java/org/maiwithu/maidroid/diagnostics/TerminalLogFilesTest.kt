package org.maiwithu.maidroid.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneOffset

class TerminalLogFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    // 2026-11-01 12:30:00.250 UTC
    private val fixedTime = 1_793_536_200_250L

    private fun logFiles(dir: File, fileLimit: Long = 1024, totalLimit: Long = 10_000) = TerminalLogFiles(
        dir = dir,
        now = { fixedTime },
        zoneId = ZoneOffset.UTC,
        fileLimitBytes = fileLimit,
        totalLimitBytes = totalLimit
    )

    @Test
    fun linesGetTimestampAndSessionFileName() {
        val dir = temp.newFolder("logs")
        val files = logFiles(dir)
        files.append("[MaiBot] hello")
        files.append("第二行")
        files.close()

        val file = File(dir, "terminal-20261101-123000.log")
        assertEquals(file, files.currentFile)
        assertEquals(
            "11-01 12:30:00.250 [MaiBot] hello\n11-01 12:30:00.250 第二行\n",
            file.readText()
        )
    }

    @Test
    fun fullFileRollsOverToNumberedPart() {
        val dir = temp.newFolder("logs")
        val files = logFiles(dir, fileLimit = 60)
        // 每行 19 字节时间前缀 + 20 字节正文 + 换行 = 40 字节，第二行放不下就换下一个分段
        files.append("a".repeat(20))
        files.append("b".repeat(20))
        files.append("c".repeat(20))
        files.close()

        assertTrue(File(dir, "terminal-20261101-123000.log").readText().contains("aaaa"))
        assertTrue(File(dir, "terminal-20261101-123000.2.log").readText().contains("bbbb"))
        assertTrue(File(dir, "terminal-20261101-123000.3.log").readText().contains("cccc"))
    }

    @Test
    fun oldestFilesArePrunedButActiveOneKept() {
        val entries = listOf(
            LogFileEntry("terminal-old.log", 600, modifiedAt = 1),
            LogFileEntry("terminal-mid.log", 600, modifiedAt = 2),
            LogFileEntry("terminal-new.log", 600, modifiedAt = 3)
        )
        assertEquals(listOf("terminal-old.log"), logFilesToPrune(entries, "terminal-new.log", limitBytes = 1300))
        assertEquals(
            listOf("terminal-old.log", "terminal-mid.log"),
            logFilesToPrune(entries, "terminal-new.log", limitBytes = 700)
        )
        // 只剩正在写的那个时即使超限也不删
        assertEquals(
            listOf("terminal-mid.log", "terminal-new.log"),
            logFilesToPrune(entries, "terminal-old.log", limitBytes = 100)
        )
        assertEquals(emptyList<String>(), logFilesToPrune(entries, null, limitBytes = 1800))
    }

    @Test
    fun tailDropsTheCutFirstLine() {
        val file = temp.newFile("tail.log")
        file.writeText("first line\nsecond line\nthird\n")
        assertEquals("second line\nthird\n", readTail(file, maxBytes = 20))
        assertEquals("first line\nsecond line\nthird\n", readTail(file, maxBytes = 1000))
        assertEquals("", readTail(File(temp.root, "missing.log"), maxBytes = 10))
    }
}
