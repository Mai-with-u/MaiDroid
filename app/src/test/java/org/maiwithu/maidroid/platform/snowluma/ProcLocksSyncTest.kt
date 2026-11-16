package org.maiwithu.maidroid.platform.snowluma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProcLocksSyncTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `collects only lock lines from own uid qq processes and writes snapshot atomically`() {
        val procRoot = tempFolder.newFolder("proc")
        // 本 UID 的 qq 进程：应当采集
        createProc(
            procRoot,
            pid = 201,
            uid = 10217,
            comm = "qq",
            fdInfos = mapOf(
                "10" to "pos:\t0\nflags:\t02\nlock:\t1: POSIX  ADVISORY  WRITE 201 103:3a:417347 0 EOF\n",
                "11" to "pos:\t0\nflags:\t00\n"
            )
        )
        // 自身 PID（即便同名同 UID）：应当跳过
        createProc(
            procRoot,
            pid = 100,
            uid = 10217,
            comm = "qq",
            fdInfos = mapOf("1" to "lock:\t1: POSIX  ADVISORY  WRITE 100 103:3a:999 0 EOF\n")
        )
        // 其他 UID 的 qq 进程：应当跳过
        createProc(
            procRoot,
            pid = 202,
            uid = 10999,
            comm = "qq",
            fdInfos = mapOf("1" to "lock:\t1: POSIX  ADVISORY  WRITE 202 103:3a:888 0 EOF\n")
        )
        // 本 UID 的非 qq 进程：应当跳过
        createProc(
            procRoot,
            pid = 203,
            uid = 10217,
            comm = "python",
            fdInfos = mapOf("1" to "lock:\t1: POSIX  ADVISORY  WRITE 203 103:3a:777 0 EOF\n")
        )

        val collected = ProcLocksSync.collectQqLockLines(procRoot, myUid = 10217, myPid = 100)
        assertEquals(listOf("1: POSIX  ADVISORY  WRITE 201 103:3a:417347 0 EOF"), collected)

        val target = File(tempFolder.newFolder("tmp"), ".maidroid-proc-locks")
        target.writeText("stale content")
        ProcLocksSync.writeProcLocksSnapshot(target, procRoot, myUid = 10217, myPid = 100)
        assertEquals("1: POSIX  ADVISORY  WRITE 201 103:3a:417347 0 EOF\n", target.readText())

        ProcLocksSync.clear(target)
        assertTrue(target.isFile)
        assertEquals("", target.readText())
    }

    private fun createProc(
        procRoot: File,
        pid: Int,
        uid: Int,
        comm: String,
        fdInfos: Map<String, String>
    ) {
        val dir = File(procRoot, pid.toString()).apply { mkdirs() }
        File(dir, "status").writeText("Name:\t$comm\nUid:\t$uid\t$uid\t$uid\t$uid\n")
        File(dir, "comm").writeText("$comm\n")
        val fdInfoDir = File(dir, "fdinfo").apply { mkdirs() }
        fdInfos.forEach { (fd, content) -> File(fdInfoDir, fd).writeText(content) }
    }
}
