package org.maiwithu.maidroid.platform.snowluma

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * 容器里的 SnowLuma 客户端（Xvfb + LinuxQQ + 注入 QQ 的 SnowLuma）。由 AppDependencies 持有唯一实例，
 * 前台服务负责启停，“重启 QQ”时只重启它。停止时先请 SnowLuma 保存好数据退出，再请 QQ 正常下线，超时才强制清理。
 *
 * 分两级自动恢复（2026-11-08 凌晨节点被后台管理单杀、宿主侧无人发现，麦麦离线 26 小时后加上的）：
 * 第一级在容器里的启动脚本内部，node 意外退出原地重起新节点、QQ 和登录都保住（见 SnowLumaScripts 的保育注释）；
 * 第二级在这里，proot 意外退出（QQ 崩了、保育放弃了、宿主侧误杀）按退避整树重启，最多连续 3 次，
 * 稳定跑满 10 分钟后计数清零。2026-11-09 下午两棵容器树被外部因素每隔几分钟整树 SIGKILL 一轮，
 * 预算用尽后旧的实现直接停手，麦麦-node 链路就死了没人救——现在改为：预算用尽只是挂起一段冷却期
 * （不立刻再拉，避免重启风暴），冷却结束自动把计数清零重新拉起。整树重启会让 QQ 重新登录，
 * 但实测今天 9 轮都是静默重登录，不需要用户动手，所以宁可自愈也别停手。
 * 只有 QqClientChoice 记着启用 SnowLuma 时才会启动，和 NapCat 二选一。
 */
internal class SnowLumaProcessSupervisor(
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

        // 意外退出后整树重启的退避节奏和上限（和 MaiBot 监管一致的节奏）。
        const val INITIAL_RESTART_DELAY_MS = 5_000L
        const val MAX_RESTART_DELAY_MS = 60_000L
        const val BACKOFF_MULTIPLIER = 2.0
        const val MAX_CONSECUTIVE_RESTARTS = 3

        // 上一轮稳定跑满这么久，下次意外退出前把连续重启计数清零。
        const val STABLE_RUNTIME_MS = 10 * 60 * 1000L

        // 预算用尽后的冷却时长：歇这么久再把计数清零重新拉起，而不是停手等手动处理。
        // 10 分钟 = 每小时最多 ~6 组重启尝试，重启风暴也罩得住，又不会长时间扔下麦麦。
        const val RESTART_COOLDOWN_MS = 10 * 60 * 1000L
    }

    private val files = SnowLumaFiles(paths)
    private val scope = backgroundScope("SnowLuma 进程监管", Dispatchers.IO)
    private val lock = Any()

    // 停止、重启、安全退出依次执行，免得两次停止同时去请 QQ 退出、清理残留。
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

    /** 进程在跑，或者正在启动（清理残留、生成启动脚本的阶段）。 */
    val isRunning: Boolean
        get() = process?.isAlive == true || job?.isActive == true

    /**
     * 没装 SnowLuma、现在启用的不是 SnowLuma、已在运行、正在安全退出或电量低暂停着时什么也不做。否则在后台：
     * 需要时换装、补迁移 MaiBot 侧的 QQ 适配器 -> 清理残留进程 -> 重新生成启动脚本 -> 挑好 WebUI 端口 ->
     * 前台运行启动脚本，把输出加 [SnowLuma] 前缀写进终端日志。
     */
    fun start() {
        synchronized(lock) {
            if (shuttingDown || pausedForLowBattery || isRunning) return
            if (!files.isSnowLumaReady() || !choice.isActive(QqClient.SnowLuma)) return

            RuntimeFileModes.ensureExecutableBits(paths)
            job = scope.launch { superviseRuntime() }
        }
    }

    /** 停掉 SnowLuma 和 QQ：先请它们正常退出，再清理剩下的 Xvfb 等进程；在后台执行。 */
    fun stop() {
        scope.launch { stopAndWait() }
    }

    /** 和 stop 一样，但挂起到停下；切换到 NapCat、手机关机前用它。 */
    suspend fun stopAndWait(): ProcessExitOutcome = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { stopLocked() }
    }

    /** 安全退出：之后的启动请求一律拦下 -> 照常停止。挂起到完全停下。 */
    suspend fun shutdownForExit(): ProcessExitOutcome = withContext(Dispatchers.IO) {
        synchronized(lock) { shuttingDown = true }
        lifecycleMutex.withLock { stopLocked() }
    }

    /** 电量低、又没插电（规则见 LowBatteryPolicy）：之后的启动请求先拦下 -> 照常停止。挂起到停下。 */
    suspend fun pauseForLowBattery(): ProcessExitOutcome {
        synchronized(lock) { pausedForLowBattery = true }
        return stopAndWait()
    }

    /** 电量恢复：解除拦截。不自己拉起，由前台服务决定要不要启动。 */
    fun resumeAfterLowBattery() {
        synchronized(lock) { pausedForLowBattery = false }
    }

    /** 只重启 SnowLuma 和 QQ（QQ 随之下线，再在 QQ 窗口里登录），不动 MaiBot。 */
    suspend fun restart() = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { stopLocked() }
        start()
    }

    /**
     * 停止、重启、切换和安全退出共用的停止流程：
     * 在跑就先请 SnowLuma 保存好数据退出（它退出不会让 QQ 下线）-> 再请 QQ 主进程自己下线退出 ->
     * 清掉启动脚本放到后台的 Xvfb 和其余残留，proot 随之结束 -> 兜底：取消读日志协程、强杀还没退的 proot。
     * 结果按 QQ 的退出情况算：QQ 下线才是用户关心的。还在启动阶段时 QQ 还没起来，直接取消启动并清掉残留。
     */
    private suspend fun stopLocked(): ProcessExitOutcome {
        val (currentProcess, runningJob) = synchronized(lock) { process to job }
        if (currentProcess?.isAlive != true) {
            terminateProcess()
            if (runningJob?.isActive == true) {
                runMaintenance(SnowLumaScripts.cleanup())
                withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningJob.join() }
            }
            return ProcessExitOutcome.NotRunning
        }

        TerminalLog.append("[SnowLuma] 停止 SnowLuma：先请它保存好数据退出，再请 QQ 正常下线")
        val snowLumaResult = commandRunner.run(SnowLumaScripts.requestSnowLumaExit()) { line ->
            if (line.isNotBlank()) TerminalLog.append(line)
        }
        if (requestedExitOf(snowLumaResult) == RequestedExit.StillRunning) {
            TerminalLog.append("[SnowLuma] SnowLuma 没有自己退出，稍后强制清理")
        }
        val qqResult = commandRunner.run(SnowLumaScripts.requestQqExit()) { line ->
            if (line.isNotBlank()) TerminalLog.append(line)
        }
        val outcome = when (requestedExitOf(qqResult)) {
            RequestedExit.Exited -> ProcessExitOutcome.Exited
            RequestedExit.NotFound -> ProcessExitOutcome.NotRunning
            RequestedExit.StillRunning -> {
                TerminalLog.append("[SnowLuma] QQ 没有自己退出，强制清理")
                ProcessExitOutcome.ForceStopped
            }
        }
        runMaintenance(SnowLumaScripts.cleanup())
        currentProcess.waitFor(PROOT_EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        terminateProcess()
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningJob?.join() }
        return outcome
    }

    /**
     * 监管循环：拉起并读日志直到退出 -> 正常停止（安全退出、低电量、切平台、用户重启）就收手 ->
     * 意外退出则退避后整树重启，最多连续 [MAX_CONSECUTIVE_RESTARTS] 次。整个循环属于同一个 job，
     * 重启不会额外生出并行的监管协程；stop 的 stopLocked 取消的正是这个 job，循环里的 delay 随之退出。
     */
    private suspend fun superviseRuntime() {
        var restartDelayMs = INITIAL_RESTART_DELAY_MS
        var restarts = 0
        while (currentCoroutineContext().isActive) {
            if (shouldStopSupervising()) return
            val launchedAt = System.currentTimeMillis()
            val unexpectedExit = runOnce()
            if (shouldStopSupervising()) return
            // 上轮稳定跑满了：算不上连着失败，计数和退避都清零，让以后每次意外退出都有完整的重启预算。
            if (System.currentTimeMillis() - launchedAt >= STABLE_RUNTIME_MS) {
                if (restarts > 0) TerminalLog.append("[SnowLuma] 上轮已稳定运行 10 分钟，整树重启计数清零")
                restarts = 0
                restartDelayMs = INITIAL_RESTART_DELAY_MS
            }
            // 主动交出的退出（脚本缺失、被下令放弃）：不管预算还剩多少都不重启。
            if (!unexpectedExit) return
            // 预算用尽（连着不稳多半是外因在成批杀整树）：不再立刻重启，挂起一段冷却期后把
            // 计数清零继续拉。delay 是可取消的，用户下令停止/低电量暂停时这里会随 job 取消退出。
            if (restarts >= MAX_CONSECUTIVE_RESTARTS) {
                TerminalLog.append(
                    "[SnowLuma] 已连续整树重启 $MAX_CONSECUTIVE_RESTARTS 次仍不稳，" +
                        "挂起 ${RESTART_COOLDOWN_MS / 60000} 分钟后自动再试"
                )
                delay(RESTART_COOLDOWN_MS)
                if (shouldStopSupervising()) return
                TerminalLog.append("[SnowLuma] 冷却结束，整树重启计数清零，重新拉起 SnowLuma")
                restarts = 0
                restartDelayMs = INITIAL_RESTART_DELAY_MS
                continue
            }
            restarts++
            TerminalLog.append("[SnowLuma] SnowLuma 意外退出，${restartDelayMs / 1000} 秒后整树重启（第 $restarts/$MAX_CONSECUTIVE_RESTARTS 次）")
            delay(restartDelayMs)
            restartDelayMs = (restartDelayMs * BACKOFF_MULTIPLIER).toLong().coerceAtMost(MAX_RESTART_DELAY_MS)
        }
    }

    // 被主动停止（安全退出、低电量暂停）或平台没在用时，监管循环不再拉起。
    private fun shouldStopSupervising() =
        shuttingDown || pausedForLowBattery || !choice.isActive(QqClient.SnowLuma) || !files.isSnowLumaReady()

    /**
     * 跑一轮：需要时换装新版 SnowLuma 适配器、补迁移 MaiBot 侧的 QQ 适配器 -> 清理残留进程 -> 重新生成启动脚本 ->
     * 挑好 WebUI 端口 -> 前台运行启动脚本，把输出加 [SnowLuma] 前缀写进终端日志。
     * 返回这轮的退出是否属于「意外退出」（true 时监管循环会整树重启）：被下令放弃、启动脚本根本缺失都算不上。
     */
    private suspend fun runOnce(): Boolean {
        // 需要时换装新版 SnowLuma 适配器、补迁移它的配置（和 NapCat 共用，见 QqAdapterUpkeep）
        adapterUpkeep.ensureBeforeStart(QqClient.SnowLuma)
        runMaintenance(SnowLumaScripts.cleanup())
        runMaintenance(SnowLumaScripts.prepareRuntime())

        if (!files.launcher.isFile) {
            TerminalLog.append("[SnowLuma] 未找到 ${SnowLumaScripts.LAUNCHER}，暂不启动 SnowLuma")
            return false
        }

        val webUiPort = pickWebUiPort()
        TerminalLog.append("")
        TerminalLog.append("[SnowLuma] 启动: bash ${SnowLumaScripts.LAUNCHER}")
        val ownJob = currentCoroutineContext()[Job]
        var startedProcess: Process? = null
        try {
            val currentProcess = commandRunner.start(
                SnowLumaScripts.launch(),
                extraEnvironment = mapOf(SnowLumaScripts.WEBUI_PORT_ENV to webUiPort.toString())
            )
            startedProcess = currentProcess
            // 停止可能恰好在启动途中发生：要么停止流程看得到这个进程、请它们退出，
            // 要么这里看到本协程已被取消、正在退出或暂停着，自己把刚起的进程结束掉（QQ 还没登录，没有要保存的）。
            val abandoned = synchronized(lock) {
                val stale = shuttingDown || pausedForLowBattery || ownJob?.isActive == false
                if (!stale) process = currentProcess
                stale
            }
            if (abandoned) {
                currentProcess.killProot()
                return false
            }
            currentProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    TerminalLog.append("[SnowLuma] ${rawLine.trimEnd('\r')}")
                }
            }
            val exitCode = currentProcess.waitFor()
            // QQ 在前台跑（启动脚本随 QQ 退出）：不管退出码是几，只要不是被下令停掉，都交给监管循环整树重启。
            TerminalLog.append("[SnowLuma] 进程退出码: $exitCode")
            return true
        } catch (e: Exception) {
            TerminalLog.append("[SnowLuma] 启动异常: ${e.message.orEmpty()}")
            // 宿主侧命令没跑成（proot 拉不起来等）：还允许监管循环按退避重试几次，别把一次抖动当永久失败。
            return true
        } finally {
            // 只清自己这一轮的引用，迟迟收尾的旧一轮不能把重启后新进程的引用清掉。
            synchronized(lock) {
                if (process === startedProcess) process = null
            }
        }
    }

    /**
     * 给 WebUI 挑端口：手机上所有应用共用回环端口，默认的 5099 被别的程序占着时 SnowLuma 的 WebUI 起不来，
     * 宿主侧还会连到别人的服务上。所以先试 5099，占着就让系统给一个空端口；挑中的端口由启动脚本记下，宿主侧照着连。
     */
    private fun pickWebUiPort(): Int {
        val loopback = InetAddress.getByName("127.0.0.1")
        val preferred = runCatching {
            ServerSocket().use { it.bind(InetSocketAddress(loopback, SnowLumaScripts.WEBUI_DEFAULT_PORT)) }
            SnowLumaScripts.WEBUI_DEFAULT_PORT
        }.getOrNull()
        if (preferred != null) return preferred
        val fallback = runCatching { ServerSocket(0, 1, loopback).use { it.localPort } }
            .getOrDefault(SnowLumaScripts.WEBUI_DEFAULT_PORT)
        TerminalLog.append("[SnowLuma] 端口 ${SnowLumaScripts.WEBUI_DEFAULT_PORT} 被别的程序占着，SnowLuma 管理页改用 $fallback")
        return fallback
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

    // 清理残留进程、生成启动脚本这类维护命令：只记非空输出，失败不中断后续启动。
    private suspend fun runMaintenance(script: ContainerScript) {
        val result = commandRunner.run(script) { line ->
            if (line.isNotBlank()) TerminalLog.append(line)
        }
        if (!result.success) {
            TerminalLog.append("[SnowLuma] maintenance command exited with code: ${result.exitCode}")
        }
    }
}
