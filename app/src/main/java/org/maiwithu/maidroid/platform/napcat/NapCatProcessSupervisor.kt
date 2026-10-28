package org.maiwithu.maidroid.platform.napcat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.log.TerminalLog
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.terminateGracefully

/**
 * 容器里的 NapCat 进程（Xvfb + 注入了 NapCat 的 LinuxQQ）。由 AppDependencies 持有唯一实例，
 * 前台服务负责启停，退出 QQ 登录时只重启它。
 *
 * 与 MaiBot 不同，NapCat 意外退出后不自动重启：QQ 掉线后要重新扫码，自动拉起也登不回去。
 */
internal class NapCatProcessSupervisor(
    private val paths: RuntimePaths,
    private val commandRunner: ContainerCommandRunner
) {
    private companion object {
        // 重启时等旧一轮读日志协程收尾的上限。
        const val RESTART_JOIN_TIMEOUT_MS = 5_000L
    }

    private val files = NapCatFiles(paths)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var job: Job? = null

    /** 进程在跑，或者正在启动（清理残留、生成 launcher 的阶段）。 */
    val isRunning: Boolean
        get() = process?.isAlive == true || job?.isActive == true

    /**
     * 没装 NapCat 或已在运行时什么也不做。否则在后台：
     * 清理残留进程 -> 重新生成 launcher.sh -> 前台运行 launcher.sh，把输出加 [NapCat] 前缀写进终端日志。
     */
    fun start() {
        synchronized(lock) {
            if (isRunning) return
            if (!files.isInstalled()) return

            RuntimeFileModes.ensureExecutableBits(paths)
            job = scope.launch { runOnce() }
        }
    }

    /** 停掉 NapCat（QQ 随之下线），再在后台清理残留的 qq / Xvfb 进程。 */
    fun stop() {
        terminateProcess()
        scope.launch { runMaintenance(NapCatScripts.cleanup()) }
    }

    /**
     * 只重启 NapCat（QQ 随之下线重登），不动 MaiBot。
     * 停旧进程 -> 等旧的读日志协程收尾（避免它的 finally 把新进程引用清掉）-> 重新启动；
     * 新一轮启动前会先跑清理命令，杀掉残留的 qq / Xvfb。
     */
    suspend fun restart() = withContext(Dispatchers.IO) {
        val previousJob = job
        terminateProcess()
        withTimeoutOrNull(RESTART_JOIN_TIMEOUT_MS) { previousJob?.join() }
        start()
    }

    private suspend fun runOnce() {
        runMaintenance(NapCatScripts.cleanup())
        runMaintenance(NapCatScripts.prepareRuntime())

        if (!files.launcher.isFile) {
            TerminalLog.append("[NapCat] 未找到 /root/launcher.sh，暂不启动 NapCat")
            return
        }

        TerminalLog.append("")
        TerminalLog.append("[NapCat] 启动: bash /root/launcher.sh")
        try {
            val currentProcess = commandRunner.start(NapCatScripts.launch())
            process = currentProcess
            currentProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    TerminalLog.append("[NapCat] ${rawLine.trimEnd('\r')}")
                }
            }
            val exitCode = currentProcess.waitFor()
            TerminalLog.append("[NapCat] 进程退出码: $exitCode")
        } catch (e: Exception) {
            TerminalLog.append("[NapCat] 启动异常: ${e.message.orEmpty()}")
        } finally {
            process = null
        }
    }

    // 取消启动协程并结束当前进程（先 SIGTERM，3 秒后强杀）。
    private fun terminateProcess() {
        synchronized(lock) {
            job?.cancel()
            job = null
            process?.terminateGracefully()
            process = null
        }
    }

    // 清理残留进程、生成 launcher.sh 这类维护命令：只记非空输出，失败不中断后续启动。
    private suspend fun runMaintenance(script: ContainerScript) {
        val result = commandRunner.run(script) { line ->
            if (line.isNotBlank()) TerminalLog.append(line)
        }
        if (!result.success) {
            TerminalLog.append("[NapCat] maintenance command exited with code: ${result.exitCode}")
        }
    }
}
