package org.maiwithu.maidroid.maibot

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ProcessExitOutcome
import org.maiwithu.maidroid.runtime.RequestedExit
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.runtime.killProot
import org.maiwithu.maidroid.runtime.requestedExitOf
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.webui.MaiBotWebUi
import java.util.concurrent.TimeUnit

/**
 * 管理容器里 MaiBot 进程的生命周期。由 AppDependencies 持有唯一实例，前台服务和在线更新共用同一份运行状态。
 *
 * - 用 MaiBotScripts.launch（installation.md 里的 uv 命令）启动 MaiBot，输出逐行写进终端日志
 * - 进程意外退出时按指数退避自动重启
 * - 停止、重启时在容器里请 MaiBot 自己退出、保存好数据，超时才强杀，再清理容器里的残留进程（stopLocked）
 * - 安全退出时先等进行中的维护做完，之后永久拦下启动请求（shutdownForExit）
 * - 维护期间（如在线更新）拦截所有启动请求
 * - 手机电量低、又没插电时先停下并拦下启动请求，插上电后解除（pauseForLowBattery）
 *
 * 启动、停止、重启都在同一把生命周期锁里依次执行，
 * 避免服务启动和主页唤醒几乎同时到达时拉起两个 MaiBot 抢占 WebUI 端口。
 */
internal class MaiBotProcessSupervisor(
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner,
    private val webUiPortTaken: () -> Boolean = MaiBotWebUi::isPortTaken
) {

    companion object {
        // TAG 是 logcat 标签；LOG_PREFIX 是写进终端输出（TerminalLog）的行首前缀，两者不是一回事。
        private const val TAG = "MaiBotProcessSupervisor"
        private const val LOG_PREFIX = "[MaiBot]"

        /** 每次拉起 MaiBot 都会打这一行；QQ 官方机器人据此判断之前的连接报错已经属于上一轮。 */
        const val PROCESS_STARTED_LOG = "$LOG_PREFIX Process started"

        // 意外退出后的重启间隔：5 秒起步，每次翻倍，最长 1 分钟。
        private const val INITIAL_RESTART_DELAY_MS = 5_000L
        private const val MAX_RESTART_DELAY_MS = 60_000L
        private const val BACKOFF_MULTIPLIER = 2.0

        // 停止时等待监管协程退出的上限；读日志是阻塞调用，进程已被杀掉后通常会立即返回。
        private const val STOP_JOIN_TIMEOUT_MS = 10_000L

        // 安全退出时：等在线更新做完的轮询间隔；Runner 退出后等 proot 跟着结束的上限。
        private const val MAINTENANCE_POLL_MS = 500L
        private const val PROOT_EXIT_WAIT_MS = 5_000L

        // WebUI 端口被别的程序占着时，隔多久再看一次空出来没有。
        private const val PORT_RECHECK_MS = 5_000L
    }

    private val scope = backgroundScope("MaiBot 进程监管", Dispatchers.IO)
    private val lifecycleMutex = Mutex()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var superviseJob: Job? = null

    // 每次启动换一个代号，停止时作废当前代号；旧一轮监管协程发现代号过期就不再重启。
    // 只在 lifecycleMutex 内修改。
    @Volatile
    private var generation = 0

    @Volatile
    private var maintenanceInProgress = false

    // 安全退出开始后永久拦下所有启动请求；退出完成时整个应用进程都会结束，不需要复位。
    @Volatile
    private var shuttingDown = false

    // 电量低时暂停期间拦下所有启动请求，电量恢复（插上电）时由前台服务解除，见 pauseForLowBattery。
    @Volatile
    private var pausedForLowBattery = false

    private val _status = MutableStateFlow(MaiBotProcessStatus())

    /** 进程是否活着、意外退出过几次、为什么没能拉起；WebUI 能不能访问要另看 MaiBotWebUi 的探测。 */
    val status: StateFlow<MaiBotProcessStatus> = _status.asStateFlow()

    /** 启动 MaiBot；已在运行或正在维护时不做任何事。 */
    fun start() {
        // 新的启动请求先清掉上一次被拦下的原因，免得等 WebUI 的一方在 startLocked 跑到之前读到旧结论。
        _status.update { it.copy(launchBlockedReason = null) }
        scope.launch {
            lifecycleMutex.withLock { startLocked() }
        }
    }

    /** 主动停止 MaiBot（先请它保存好数据再退出），停止后不会自动重启。 */
    fun stop() {
        scope.launch { stopAndWait() }
    }

    /** 和 stop 一样，但挂起到 MaiBot 停下；手机关机前用它抢那几秒（见 MaiBotRuntimeService 的关机广播）。 */
    suspend fun stopAndWait(): ProcessExitOutcome = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { stopLocked() }
    }

    /** 先停再启（停的时候同样请 MaiBot 保存好数据再退出），用于装好平台适配器后让 MaiBot 重新加载插件等场景。 */
    fun restart() {
        scope.launch {
            lifecycleMutex.withLock {
                stopLocked()
                startLocked()
            }
        }
    }

    /**
     * 手机改了时区：先把容器的系统时区改过去 -> MaiBot 正在跑就重启。
     * 已经在跑的 MaiBot 启动时就读定了时区，不重启会一直按旧时区报时；没在跑的下次启动自然用上新时区。
     */
    fun followPhoneTimeZone() {
        scope.launch {
            val zoneId = commandRunner.syncTimeZone() ?: return@launch
            if (!_status.value.running) return@launch
            appendLog("$LOG_PREFIX Phone time zone changed to $zoneId, restarting")
            lifecycleMutex.withLock {
                stopLocked()
                startLocked()
            }
        }
    }

    /**
     * 停住 MaiBot 执行一段维护逻辑（如在线更新）。
     *
     * 先挂上维护标记 -> 停掉 MaiBot 并清理残留进程 -> 执行 block -> 摘掉维护标记。
     * 维护期间唤醒按钮、开机广播、平台安装后的重启等启动请求都会被拦下；
     * 维护结束只解除拦截，不自动拉起，由调用方决定何时重新启动。
     */
    suspend fun <T> runWhileStopped(block: suspend () -> T): T {
        maintenanceInProgress = true
        try {
            // 安全退出已经开始：不再开始新的维护（退出只等已经在做的维护），免得更新做到一半应用就被关掉。
            // 先挂维护标记再看退出标记，和 shutdownForExit 的顺序相反，两边同时发生时至少有一边能看到对方。
            if (shuttingDown) throw CancellationException("MaiDroid is exiting")
            lifecycleMutex.withLock { stopLocked() }
            return block()
        } finally {
            maintenanceInProgress = false
        }
    }

    /**
     * 安全退出：之后的启动请求一律拦下 -> 在线更新还在做就等它做完 -> 照常停止（请 MaiBot 自己退出、保存好数据）。
     * 挂起到 MaiBot 完全停下。
     * onProgress 报告当前在做什么：true 表示在等维护做完，false 表示已经在请 MaiBot 退出，供退出进度显示。
     */
    suspend fun shutdownForExit(onProgress: (waitingForMaintenance: Boolean) -> Unit): ProcessExitOutcome {
        shuttingDown = true
        if (maintenanceInProgress) {
            onProgress(true)
            appendLog("$LOG_PREFIX Waiting for the running update to finish before exiting")
            while (maintenanceInProgress) delay(MAINTENANCE_POLL_MS)
        }
        onProgress(false)
        return lifecycleMutex.withLock { stopLocked() }
    }

    /**
     * 电量低、又没插电（规则见 LowBatteryPolicy）：之后的启动请求先拦下 -> 照常停止（请 MaiBot 保存好数据再退出）。
     * 挂起到 MaiBot 停下。在线更新正在做时 MaiBot 本来就停着，更新照常做完，做完后的拉起也会被拦下。
     * 状态里标出 lowBatteryPaused，唤醒页和首次引导据此说明为什么没启动。
     */
    suspend fun pauseForLowBattery(): ProcessExitOutcome {
        pausedForLowBattery = true
        _status.update { it.copy(lowBatteryPaused = true) }
        return stopAndWait()
    }

    /** 电量恢复：解除拦截。不自己拉起，由前台服务决定要不要启动（只为首次引导开的服务不拉起）。 */
    fun resumeAfterLowBattery() {
        pausedForLowBattery = false
        _status.update { it.copy(lowBatteryPaused = false) }
    }

    // 请 Runner 自己退出；确认退出后再等一会儿 proot 结束，让 MaiBot 最后几行关闭日志读完再往下走。
    private suspend fun requestExit(currentProcess: Process): ProcessExitOutcome {
        appendLog("$LOG_PREFIX Asking MaiBot to shut down and save its data")
        val result = commandRunner.run(MaiBotScripts.requestExit()) { line ->
            if (line.isNotBlank()) appendLog(line)
        }
        if (requestedExitOf(result) != RequestedExit.Exited) {
            appendLog("$LOG_PREFIX MaiBot did not exit by itself, force stopping")
            return ProcessExitOutcome.ForceStopped
        }
        withContext(Dispatchers.IO) { currentProcess.waitFor(PROOT_EXIT_WAIT_MS, TimeUnit.MILLISECONDS) }
        return ProcessExitOutcome.Exited
    }

    private fun startLocked() {
        if (shuttingDown) {
            appendLog("$LOG_PREFIX MaiDroid is exiting, start request ignored")
            return
        }
        if (pausedForLowBattery) {
            appendLog("$LOG_PREFIX Battery is low, start request ignored until the phone is charging")
            return
        }
        if (maintenanceInProgress) {
            appendLog("$LOG_PREFIX Under maintenance, start request ignored")
            return
        }
        if (superviseJob?.isActive == true) return

        RuntimeFileModes.ensureExecutableBits(paths)
        val notReady = readiness.maiBotLaunchRequirements().firstOrNull { !it.ready }
        if (notReady != null) {
            appendLog(
                "$LOG_PREFIX Runtime validation failed: " +
                    "${notReady.name} is not ready: ${notReady.path}. ${notReady.hint}"
            )
            // 记下没拉起的原因，等 WebUI 的一方（OOBE）据此立刻报错，而不是空等到超时。
            _status.update { it.copy(launchBlockedReason = "${notReady.name} 未就绪：${notReady.hint}") }
            return
        }
        _status.update { it.copy(launchBlockedReason = null) }

        val currentGeneration = ++generation
        superviseJob = scope.launch { superviseProcess(currentGeneration) }
    }

    /**
     * 停止、重启、维护前和安全退出共用的停止流程：
     * 作废当前一轮（MaiBot 退出后监管协程不会再把它拉起来）-> 在跑就在容器里请 Runner 自己退出，
     * 等它保存好数据（空闲时约 3 秒，最多 80 秒）-> 兜底：强杀还没退的 proot（超时、Runner 还没起来时），
     * 等监管协程收尾 -> 按进程名清理容器里的残留（强杀 proot 后脱离出来的进程也在这一步结束）。
     */
    private suspend fun stopLocked(): ProcessExitOutcome {
        generation++
        val runningJob = superviseJob
        superviseJob = null
        val currentProcess = process
        val outcome = if (currentProcess?.isAlive == true) {
            requestExit(currentProcess)
        } else {
            ProcessExitOutcome.NotRunning
        }
        killProcess()
        if (runningJob != null) {
            runningJob.cancel()
            withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningJob.join() }
        }
        cleanupStaleProcesses()
        _status.update { it.copy(running = false) }
        return outcome
    }

    /**
     * 监管循环：清理残留 -> 拉起 MaiBot -> 读日志直到退出 -> 非主动停止则退避后重启。
     * 整个循环属于同一个 job，重启不会额外生出并行的监管协程。
     */
    private suspend fun superviseProcess(ownGeneration: Int) {
        var restartDelayMs = INITIAL_RESTART_DELAY_MS
        cleanupStaleProcesses()
        while (currentCoroutineContext().isActive && !shouldStayStopped(ownGeneration)) {
            // 每次拉起前（含意外退出后重启前）都看一眼 WebUI 端口，等的时候被停止就不再拉起
            if (!awaitFreeWebUiPort(ownGeneration)) break
            runProcessOnce(ownGeneration)
            if (shouldStayStopped(ownGeneration)) break

            appendLog("$LOG_PREFIX Restarting in ${restartDelayMs}ms...")
            delay(restartDelayMs)
            restartDelayMs = (restartDelayMs * BACKOFF_MULTIPLIER)
                .toLong()
                .coerceAtMost(MAX_RESTART_DELAY_MS)
        }
    }

    private fun shouldStayStopped(ownGeneration: Int): Boolean =
        ownGeneration != generation || maintenanceInProgress

    /**
     * 拉起前看一眼 WebUI 端口：自己的 MaiBot 这时没在跑，端口上却有程序在听，就是被别人占着
     * （另一个 MaiDroid、Termux 里的 MaiBot 或别的服务）。这时拉起来也绑不上端口、只会反复重启，
     * 探测 WebUI 还会连到那个程序上，被当成麦麦已经上线（2026-11 真机上装了两个 MaiDroid 时实测如此）。
     * 所以先不拉起：在状态里标出端口被占（OOBE、唤醒页据此报 PORT-BUSY）-> 每 5 秒再看一次 -> 空出来就接着启动。
     * 返回 false 表示等的时候被停止了。
     */
    private suspend fun awaitFreeWebUiPort(ownGeneration: Int): Boolean {
        if (!webUiPortTaken()) return true
        appendLog(
            "[MaiDroid] 端口 ${MaiBotWebUi.PORT} 被别的程序占着（另一个 MaiDroid、Termux 里的 MaiBot 或别的服务），" +
                "麦麦先不启动，端口空出来后自动启动"
        )
        _status.update { it.copy(webUiPortTaken = true) }
        try {
            while (webUiPortTaken()) {
                if (shouldStayStopped(ownGeneration)) return false
                delay(PORT_RECHECK_MS)
            }
        } finally {
            _status.update { it.copy(webUiPortTaken = false) }
        }
        if (shouldStayStopped(ownGeneration)) return false
        appendLog("[MaiDroid] 端口 ${MaiBotWebUi.PORT} 空出来了，启动麦麦")
        return true
    }

    // 跑一轮 MaiBot：启动 -> 把输出逐行写进终端日志 -> 等它退出。阻塞调用，只在监管协程（IO 线程）里执行。
    private fun runProcessOnce(ownGeneration: Int) {
        var startedProcess: Process? = null
        try {
            val script = MaiBotScripts.launch()
            appendLog("$LOG_PREFIX Starting: ${commandRunner.commandLine(script).joinToString(" ")}")

            val currentProcess = commandRunner.start(script, MaiBotAgreement.environment(paths))
            startedProcess = currentProcess
            process = currentProcess
            // 进程刚起来时若恰好收到停止请求，stopLocked 可能还没看到这个进程，这里自行结束它（MaiBot 还没起来，没有要保存的）。
            if (shouldStayStopped(ownGeneration)) {
                forceKill(currentProcess)
                return
            }

            _status.update { it.copy(running = true) }
            appendLog("$PROCESS_STARTED_LOG (PID: ${currentProcess.pidOrUnknown()})")

            currentProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { appendLog(it.trimEnd('\r')) }
            }

            val exitCode = currentProcess.waitFor()
            appendLog("$LOG_PREFIX Process exited with code: $exitCode")
            recordUnexpectedExit(ownGeneration, exitCode)
        } catch (e: Exception) {
            appendLog("$LOG_PREFIX Failed to start process: ${e.message}")
            Log.e(TAG, "Failed to start MaiBot process", e)
            recordUnexpectedExit(ownGeneration, exitCode = null)
        } finally {
            // 只清理本轮自己的进程引用：停止超时后迟迟退出的旧一轮不能覆盖新一轮的运行状态。
            if (process === startedProcess) {
                process = null
                _status.update { it.copy(running = false) }
            }
        }
    }

    // 主动停止、重启、维护时进程被结束不算意外退出；其余的退出（含没能起来）计数，供 OOBE 判断是否起不来。
    private fun recordUnexpectedExit(ownGeneration: Int, exitCode: Int?) {
        if (shouldStayStopped(ownGeneration)) return
        _status.update { it.copy(exitCount = it.exitCount + 1, lastExitCode = exitCode) }
    }

    private suspend fun killProcess() {
        val currentProcess = process ?: return
        withContext(Dispatchers.IO) { forceKill(currentProcess) }
        process = null
    }

    // 兜底强杀 proot，真杀了才在终端里记一笔；MaiBot 自己退出后 proot 已经结束，不会走到这一行。
    private fun forceKill(target: Process) {
        if (target.killProot()) {
            appendLog("$LOG_PREFIX Force-killed proot")
        }
    }

    private suspend fun cleanupStaleProcesses() {
        val result = commandRunner.run(MaiBotScripts.cleanupStaleProcesses()) { line ->
            if (line.isNotBlank()) appendLog(line)
        }
        if (!result.success) {
            appendLog("$LOG_PREFIX Cleanup command exited with code: ${result.exitCode}")
        }
    }

    /** Android 的 Process 没有 pid()（Java 9+ 才有），只能反射读取，仅用于日志。 */
    private fun Process.pidOrUnknown(): String =
        runCatching {
            val pidField = javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getLong(this).toString()
        }.getOrDefault("unknown")

    private fun appendLog(line: String) {
        TerminalLog.append(line)
    }
}

/**
 * MaiBot 进程的状态快照。
 *
 * exitCount 是进程启动后意外退出（含没能启动起来）的累计次数，主动停止、重启不算；
 * 等 WebUI 的一方先记下当前值，之后看它涨了几次就知道 MaiBot 是不是起来就崩。
 * launchBlockedReason 是最近一次启动请求因启动前检查没通过而被拦下的原因，成功拉起后清空。
 * webUiPortTaken 表示 WebUI 端口被别的程序占着、正等它空出来才拉起 MaiBot。
 * lowBatteryPaused 表示手机电量低、又没插电，MaiBot 已经（或正在）保存好数据停下，插上电后才拉起。
 */
internal data class MaiBotProcessStatus(
    val running: Boolean = false,
    val exitCount: Int = 0,
    val lastExitCode: Int? = null,
    val launchBlockedReason: String? = null,
    val webUiPortTaken: Boolean = false,
    val lowBatteryPaused: Boolean = false
)
