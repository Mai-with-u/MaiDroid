package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProotCommandTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun `shellPrelude exports glibc arena and trim tuning without pinning when disabled`() {
        val paths = RuntimePaths(
            appFilesDir = tempFolder.newFolder("files"),
            nativeLibraryDir = tempFolder.newFolder("libs")
        )
        val cmd = ProotCommand(paths).commandLine(
            ContainerScript(body = "echo ok", excludeTopCpuCluster = false)
        )
        val shellBody = cmd.last()

        assertTrue(shellBody.contains("MALLOC_ARENA_MAX=2"))
        assertTrue(shellBody.contains("MALLOC_TRIM_THRESHOLD_=131072"))
        assertFalse(shellBody.contains("maidroid_exclude_top_cpu_cluster"))
        assertFalse(shellBody.contains("taskset -pc"))
    }

    @Test
    fun `excludeTopCpuCluster scans cpu directories cleanly falls back to scaling and capacity and pins outer proot`() {
        val paths = RuntimePaths(
            appFilesDir = tempFolder.newFolder("files_pin"),
            nativeLibraryDir = tempFolder.newFolder("libs_pin")
        )
        val cmd = ProotCommand(paths).commandLine(
            ContainerScript(body = "exec python bot.py", excludeTopCpuCluster = true)
        )
        val shellBody = cmd.last()

        // 直接枚举真实存在的核心目录，不再空扫 0..511 次 stat
        assertTrue(shellBody.contains("/sys/devices/system/cpu/cpu[0-9]*"))
        assertFalse(shellBody.contains("lt 512"))
        // 含 scaling_max_freq 与 cpu_capacity 两级兜底
        assertTrue(shellBody.contains("cpufreq/scaling_max_freq"))
        assertTrue(shellBody.contains("cpu_capacity"))
        // 确认父进程 cmdline 含 proot 后同步钉入同一能效簇
        assertTrue(shellBody.contains("*proot*"))
        assertTrue(shellBody.contains("taskset -pc \"\$mask\" \"\$ppid\""))
    }

    @Test
    fun `prootArguments mounts sdcard only when external storage permission is granted`() {
        val paths = RuntimePaths(
            appFilesDir = tempFolder.newFolder("files_storage"),
            nativeLibraryDir = tempFolder.newFolder("libs_storage")
        )
        val script = ContainerScript(body = "echo ok", excludeTopCpuCluster = false)

        val grantedCmd = ProotCommand(paths, mountExternalStorage = { true }).commandLine(script)
        val sdcardBindIdx = grantedCmd.indexOf("/sdcard")
        assertTrue(sdcardBindIdx > 0 && grantedCmd[sdcardBindIdx - 1] == "-b")

        val deniedCmd = ProotCommand(paths, mountExternalStorage = { false }).commandLine(script)
        assertFalse(deniedCmd.contains("/sdcard"))
    }
}
