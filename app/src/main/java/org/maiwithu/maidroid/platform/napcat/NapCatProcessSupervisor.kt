package org.maiwithu.maidroid.platform.napcat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.platform.qq.QqAdapterUpkeep
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qq.QqClientChoice
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ProcessExitOutcome
import org.maiwithu.maidroid.runtime.RequestedExit
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.killProot
import org.maiwithu.maidroid.runtime.requestedExitOf
import org.maiwithu.maidroid.terminal.TerminalLog
import java.util.concurrent.TimeUnit

/**
 * 容器里的 NapCat 进程（Xvfb + 注入了 NapCat 的 LinuxQQ）。由 AppDependencies 持有唯一实例，
 * 前台服务负责启停，退出 QQ 登录时只重启它。停止时先请 QQ 正常下线退出，超时才强制清理。
 *
 * 与 MaiBot 不同，NapCat 意外退出后不自动重启：QQ 掉线后要重新扫码，自动拉起也登不回去。
 * 只有 QqClientChoice 记着启用 NapCat 时才会启动（没有记录也算 NapCat），和 SnowLuma 二选一。
 */
internal class NapCatProcessSupervisor(
    private val paths: RuntimePaths,
    private val commandRunner: ContainerCommandRunner,
    private val adapterUpkeep: QqAdapterUpkeep,
    private val choice: QqClientChoice
) {
    private companion object {
        // 停止时等旧一轮读日志（或还在启动）的协程收尾的上限。
        const val STOP_JOIN_TIMEOUT_MS = 5_000L

        // 停止时清掉 Xvfb 后等 proot 跟着结束的上限。
        const val PROOT_EXIT_WAIT_MS = 5_000L
    }

    private val files = NapCatFiles(paths)

    private val scope = backgroundScope("NapCat 进程监管", Dispatchers.IO)
    private val lock = Any()

    // 停止、重启、安全退出依次执行，免得两次停止同时去请 QQ 退出、清理残留（比如连点两次退出 QQ 登录）。
    private val lifecycleMutex = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var job: Job? = null

    // 安全退出开始后永久拦下启动请求；退出完成时整个应用进程都会结束，不需要复位。只在 lock 内修改。
    @Volatile
    private var shuttingDown = false

    // 电量低时暂停期间拦下启动请求，电量恢复（插上电）时由前台服务解除。只在 lock 内修改。
    @Volatile
    private var pausedForLowBattery = false

    /** 进程在跑，或者正在启动（清理残留、生成 launcher 的阶段）。 */
    val isRunning: Boolean
        get() = process?.isAlive == true || job?.isActive == true

    /**
     * 没装 NapCat、现在启用的是 SnowLuma、已在运行、MaiDroid 正在安全退出或电量低暂停着时什么也不做。否则在后台：
     * 需要时换装新版 SnowLuma 适配器 -> 清理残留进程 -> 重新生成 launcher.sh ->
     * 前台运行 launcher.sh，把输出加 [NapCat] 前缀写进终端日志。
     * 只看 NapCat 本体装没装好：MaiBot 太旧、暂时换不了适配器的老用户，QQ 照样能登录。
     */
    fun start() {
        synchronized(lock) {
            if (shuttingDown || pausedForLowBattery || isRunning) return
            if (!files.isNapCatReady() || !choice.isActive(QqClient.NapCat)) return

            RuntimeFileModes.ensureExecutableBits(paths)
            job = scope.launch { runOnce() }
        }
    }

    /** 停掉 NapCat：请 QQ 正常下线退出，再清理剩下的 Xvfb 等进程；在后台执行。 */
    fun stop() {
        scope.launch { stopAndWait() }
    }

    /** 和 stop 一样，但挂起到 NapCat 停下；手机关机前用它抢那几秒（见 MaiBotRuntimeService 的关机广播）。 */
    suspend fun stopAndWait(): ProcessExitOutcome = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { stopLocked() }
    }

    /**
     * 安全退出：之后的启动请求一律拦下 -> 照常停止（请 QQ 正常下线退出）。挂起到 NapCat 完全停下。
     */
    suspend fun shutdownForExit(): ProcessExitOutcome = withContext(Dispatchers.IO) {
        synchronized(lock) { shuttingDown = true }
        lifecycleMutex.withLock { stopLocked() }
    }

    /**
     * 电量低、又没插电（规则见 LowBatteryPolicy）：之后的启动请求先拦下 -> 照常停止（请 QQ 正常下线退出）。
     * 挂起到 NapCat 停下。
     */
    suspend fun pauseForLowBattery(): ProcessExitOutcome {
        synchronized(lock) { pausedForLowBattery = true }
        return stopAndWait()
    }

    /** 电量恢复：解除拦截。不自己拉起，由前台服务决定要不要启动。 */
    fun resumeAfterLowBattery() {
        synchronized(lock) { pausedForLowBattery = false }
    }

    /**
     * 只重启 NapCat（QQ 随之下线重登），不动 MaiBot。
     * 照常停止（QQ 正常下线退出，旧的读日志协程也已收尾，它的 finally 不会把新进程引用清掉）-> 重新启动；
     * 新一轮启动前会先跑清理命令，杀掉残留的 qq / Xvfb。
     */
    suspend fun restart() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { stopLocked() }
        start()
    }

    /**
     * 停止、重启和安全退出共用的停止流程：
     * 在跑就在容器里请 QQ 主进程自己下线退出（实测 1~2 秒，最多 20 秒）-> 清掉 launcher.sh 放到后台的 Xvfb
     * 和其余残留，proot 随之结束 -> 兜底：取消读日志协程、强杀还没退的 proot -> 等协程收尾。
     * QQ 没找到或等到超时就按清理命令强制结束。还在启动阶段（换装适配器、清理残留、生成 launcher）时
     * QQ 还没起来，直接取消启动，并清掉启动途中可能已经起来的残留。
     */
    private suspend fun stopLocked(): ProcessExitOutcome {
        val (currentProcess, runningJob) = synchronized(lock) { process to job }
        if (currentProcess?.isAlive != true) {
            terminateProcess()
            if (runningJob?.isActive == true) {
                runMaintenance(NapCatScripts.cleanup())
                withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningJob.join() }
            }
            return ProcessExitOutcome.NotRunning
        }

        TerminalLog.append("[NapCat] 停止 NapCat：请 QQ 正常下线退出")
        val result = commandRunner.run(NapCatScripts.requestQqExit()) { line ->
            if (line.isNotBlank()) TerminalLog.append(line)
        }
        // 没找到 QQ：QQ 早已退出（崩溃或启动失败），只剩 Xvfb 撑着 proot，下面清理掉即可。
        val outcome = when (requestedExitOf(result)) {
            RequestedExit.Exited -> ProcessExitOutcome.Exited
            RequestedExit.NotFound -> ProcessExitOutcome.NotRunning
            RequestedExit.StillRunning -> {
                TerminalLog.append("[NapCat] QQ 没有自己退出，强制清理")
                ProcessExitOutcome.ForceStopped
            }
        }
        runMaintenance(NapCatScripts.cleanup())
        currentProcess.waitFor(PROOT_EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        terminateProcess()
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningJob?.join() }
        return outcome
    }

    private suspend fun runOnce() {
        // 需要时换装新版 SnowLuma 适配器、补迁移它的配置（和 SnowLuma 共用，见 QqAdapterUpkeep）
        adapterUpkeep.ensureBeforeStart(QqClient.NapCat)
        runMaintenance(NapCatScripts.cleanup())
        runMaintenance(NapCatScripts.prepareRuntime())

        if (!files.launcher.isFile) {
            TerminalLog.append("[NapCat] 未找到 /root/launcher.sh，暂不启动 NapCat")
            return
        }

        TerminalLog.append("")
        TerminalLog.append("[NapCat] 启动: bash /root/launcher.sh")
        val ownJob = currentCoroutineContext()[Job]
        var startedProcess: Process? = null
        try {
            val currentProcess = commandRunner.start(NapCatScripts.launch())
            startedProcess = currentProcess
            // 停止（含安全退出、低电量暂停）可能恰好在启动途中发生：terminateProcess 在同一把锁里取消本协程，
            // 所以要么停止流程看得到这个进程、请 QQ 退出，要么这里看到本协程已被取消、正在退出或暂停着，
            // 自己把刚起的进程结束掉（QQ 还没登录，没有要保存的）。
            val abandoned = synchronized(lock) {
                val stale = shuttingDown || pausedForLowBattery || ownJob?.isActive == false
                if (!stale) process = currentProcess
                stale
            }
            if (abandoned) {
                currentProcess.killProot()
                return
            }
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
            // 只清自己这一轮的引用，迟迟收尾的旧一轮不能把重启后新进程的引用清掉。
            synchronized(lock) {
                if (process === startedProcess) process = null
            }
        }
    }

    // 兜底：取消启动或读日志的协程，强杀还没退的 proot（正常停止时 QQ、Xvfb 已经退出，proot 也已结束）。阻塞调用。
    private fun terminateProcess() {
        synchronized(lock) {
            job?.cancel()
            job = null
            process?.killProot()
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
