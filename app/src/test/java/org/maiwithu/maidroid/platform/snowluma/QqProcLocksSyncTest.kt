package org.maiwithu.maidroid.platform.snowluma

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class QqProcLocksSyncTest {

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

        val collected = QqProcLocksSync.collectQqLockLines(procRoot, myUid = 10217, myPid = 100)
        assertEquals(listOf("1: POSIX  ADVISORY  WRITE 201 103:3a:417347 0 EOF"), collected)

        val target = File(tempFolder.newFolder("tmp"), ".maidroid-proc-locks")
        target.writeText("stale content")
        QqProcLocksSync.writeProcLocksSnapshot(target, procRoot, myUid = 10217, myPid = 100)
        assertEquals("1: POSIX  ADVISORY  WRITE 201 103:3a:417347 0 EOF\n", target.readText())

        QqProcLocksSync.clear(target)
        assertTrue(target.isFile)
        assertEquals("", target.readText())
    }

    @Test
    fun `skips redundant disk writes when snapshot matches and lowers sync frequency when steady`() {
        val procRoot = tempFolder.newFolder("proc_steady")
        createProc(
            procRoot,
            pid = 301,
            uid = 10217,
            comm = "qq",
            fdInfos = mapOf("10" to "lock:\t1: POSIX  ADVISORY  WRITE 301 103:3a:111 0 EOF\n")
        )
        val target = File(tempFolder.newFolder("tmp_steady"), ".maidroid-proc-locks")

        val first = QqProcLocksSync.syncProcLocksSnapshot(
            targetFile = target,
            previousContent = null,
            procRoot = procRoot,
            myUid = 10217,
            myPid = 100
        )
        assertTrue(first.wroteFile)
        assertTrue(first.hasLocks)

        val frozenMtime = 1_700_000_000_000L
        assertTrue(target.setLastModified(frozenMtime))

        // 锁内容完全一致时跳过写盘与 rename，不修改磁盘文件 mtime
        val unchanged = QqProcLocksSync.syncProcLocksSnapshot(
            targetFile = target,
            previousContent = first.content,
            procRoot = procRoot,
            myUid = 10217,
            myPid = 100
        )
        assertFalse(unchanged.wroteFile)
        assertTrue(unchanged.hasLocks)
        assertEquals(frozenMtime, target.lastModified())

        // 锁内容变化后立即恢复写盘
        createProc(
            procRoot,
            pid = 301,
            uid = 10217,
            comm = "qq",
            fdInfos = mapOf("10" to "lock:\t1: POSIX  ADVISORY  WRITE 301 103:3a:222 0 EOF\n")
        )
        val updated = QqProcLocksSync.syncProcLocksSnapshot(
            targetFile = target,
            previousContent = unchanged.content,
            procRoot = procRoot,
            myUid = 10217,
            myPid = 100
        )
        assertTrue(updated.wroteFile)
        assertTrue(updated.content.contains("222"))

        // 稳态降频与活跃探测间隔校验
        assertEquals(QqProcLocksSync.ACTIVE_SYNC_INTERVAL_MS, QqProcLocksSync.nextSyncDelayMs(hasLocks = false, unchangedStreak = 5))
        assertEquals(QqProcLocksSync.ACTIVE_SYNC_INTERVAL_MS, QqProcLocksSync.nextSyncDelayMs(hasLocks = true, unchangedStreak = 2))
        assertEquals(QqProcLocksSync.STEADY_SYNC_INTERVAL_MS, QqProcLocksSync.nextSyncDelayMs(hasLocks = true, unchangedStreak = 3))
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
