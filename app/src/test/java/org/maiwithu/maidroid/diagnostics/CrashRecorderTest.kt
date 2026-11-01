package org.maiwithu.maidroid.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.ZoneOffset

class CrashRecorderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private var clock = 1_793_536_200_250L

    private fun recorder() = CrashRecorder(
        dir = temp.root,
        header = { "MaiDroid 0.2.0（5，debug）" },
        recentLines = { listOf("[MaiBot] 最后一行") },
        now = { clock },
        zoneId = ZoneOffset.UTC
    )

    @Test
    fun crashFileHasHeaderStackAndRecentOutput() {
        val file = recorder().record("main", IllegalStateException("boom"))
        assertEquals("crash-20261101-123000-250.txt", file.name)
        val text = file.readText()
        assertTrue(text.startsWith("MaiDroid 0.2.0（5，debug）\n时间：2026-11-01 12:30:00 +00:00\n线程：main\n"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom\n\tat "))
        assertTrue(text.contains("----- 闪退前的终端输出 -----\n[MaiBot] 最后一行"))
    }

    @Test
    fun pendingCrashIsAskedOnlyOnce() {
        val recorder = recorder()
        assertNull(recorder.pendingCrash())

        recorder.record("main", IllegalStateException("first"))
        val pending = recorder.pendingCrash()
        assertEquals(CrashRecord("crash-20261101-123000-250.txt", "java.lang.IllegalStateException: first"), pending)

        recorder.acknowledge(pending!!)
        assertNull(recorder.pendingCrash())

        // 之后又闪退一次，又要问
        clock += 60_000
        recorder.record("worker", RuntimeException("second"))
        assertEquals("java.lang.RuntimeException: second", recorder.pendingCrash()?.summary)
    }

    @Test
    fun onlyNewestTenFilesAreKept() {
        val recorder = recorder()
        repeat(12) {
            clock += 1_000
            recorder.record("main", RuntimeException("crash $it"))
        }
        val files = temp.root.listFiles { file -> file.name.startsWith("crash-") }.orEmpty()
        assertEquals(10, files.size)
        assertEquals("java.lang.RuntimeException: crash 11", recorder.pendingCrash()?.summary)
    }
}
