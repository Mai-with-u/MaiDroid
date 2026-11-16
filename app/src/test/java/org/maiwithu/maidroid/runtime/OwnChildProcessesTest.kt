package org.maiwithu.maidroid.runtime

import android.system.OsConstants
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OwnChildProcessesTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `counts only own uid child processes excluding self and non-numeric entries`() {
        val procRoot = tempFolder.newFolder("proc")
        File(procRoot, "self").mkdirs()
        File(procRoot, "net").mkdirs()
        writeFakeProc(procRoot, pid = 100, uid = 10217, cmdline = "org.maiwithu.maidroid")
        writeFakeProc(procRoot, pid = 101, uid = 10217, cmdline = "python\u0000bot.py")
        writeFakeProc(procRoot, pid = 102, uid = 10217, cmdline = "qq\u0000--no-sandbox")
        writeFakeProc(procRoot, pid = 103, uid = 10999, cmdline = "python\u0000bot.py")

        assertEquals(2, countOwnChildProcesses(procRoot, myUid = 10217, myPid = 100))
    }

    @Test
    fun `kills matching processes with sigterm first and sigkill only for survivors`() = runBlocking {
        val procRoot = tempFolder.newFolder("proc")
        val exitsGracefully = writeFakeProc(procRoot, pid = 201, uid = 10217, cmdline = "python\u0000-m\u0000src.runtime")
        writeFakeProc(procRoot, pid = 202, uid = 10217, cmdline = "python\u0000-m\u0000src.plugin_runtime.runner.runner_main")
        writeFakeProc(procRoot, pid = 203, uid = 10217, cmdline = "qq\u0000--no-sandbox")
        writeFakeProc(procRoot, pid = 204, uid = 10999, cmdline = "python\u0000bot.py")

        val sent = mutableListOf<Pair<Int, Int>>()
        val logs = mutableListOf<String>()

        killOwnMatchingProcesses(
            pattern = Regex("src[.]runtime|src[.]plugin_runtime[.]runner[.]runner_main|bot[.]py"),
            processName = "stale MaiBot process",
            logPrefix = "[MaiBot] ",
            procRoot = procRoot,
            myUid = 10217,
            myPid = 100,
            signalDelayMs = 0L,
            signalSender = { pid, signal ->
                sent += pid to signal
                if (pid == 201 && signal == OsConstants.SIGTERM) {
                    exitsGracefully.deleteRecursively()
                }
            },
            onLog = { logs += it }
        )

        assertEquals(
            listOf(
                201 to OsConstants.SIGTERM,
                202 to OsConstants.SIGTERM,
                202 to OsConstants.SIGKILL
            ),
            sent
        )
        assertEquals(
            listOf(
                "[MaiBot] stopping stale MaiBot process 201",
                "[MaiBot] stopping stale MaiBot process 202",
                "[MaiBot] force stopping stale MaiBot process 202"
            ),
            logs
        )
    }

    private fun writeFakeProc(procRoot: File, pid: Int, uid: Int, cmdline: String): File {
        val dir = File(procRoot, pid.toString()).apply { mkdirs() }
        File(dir, "status").writeText("Uid:\t$uid\t$uid\t$uid\t$uid\n")
        File(dir, "cmdline").writeBytes(cmdline.toByteArray(Charsets.UTF_8))
        return dir
    }
}
