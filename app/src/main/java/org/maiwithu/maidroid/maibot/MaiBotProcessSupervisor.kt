package org.maiwithu.maidroid.maibot

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.runtime.terminateGracefully
import org.maiwithu.maidroid.terminal.TerminalLog

/**
 * 管理容器里 MaiBot 进程的生命周期。由 AppDependencies 持有唯一实例，前台服务和在线更新共用同一份运行状态。
 *
 * - 用 MaiBotScripts.launch（installation.md 里的 uv 命令）启动 MaiBot，输出逐行写进终端日志
 * - 进程意外退出时按指数退避自动重启
 * - 停止时先 SIGTERM 再强杀，并清理容器里的残留进程
 * - 维护期间（如在线更新）拦截所有启动请求
 *
 * 启动、停止、重启都在同一把生命周期锁里依次执行，
 * 避免服务启动和主页唤醒几乎同时到达时拉起两个 MaiBot 抢占 WebUI 端口。
 */
internal class MaiBotProcessSupervisor(
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner
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
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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

    /** 主动停止 MaiBot，停止后不会自动重启。 */
    fun stop() {
        scope.launch {
            lifecycleMutex.withLock { stopLocked() }
        }
    }

    /** 先停再启，用于装好平台适配器后让 MaiBot 重新加载插件等场景。 */
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
            lifecycleMutex.withLock { stopLocked() }
            return block()
        } finally {
            maintenanceInProgress = false
        }
    }

    private fun startLocked() {
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

    private suspend fun stopLocked() {
        generation++
        val runningJob = superviseJob
        superviseJob = null
        // 先杀进程让阻塞的日志读取返回，再等监管协程收尾，最后按进程名清理容器里的残留。
        killProcess()
        if (runningJob != null) {
            runningJob.cancel()
            withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningJob.join() }
        }
        cleanupStaleProcesses()
        _status.update { it.copy(running = false) }
    }

    /**
     * 监管循环：清理残留 -> 拉起 MaiBot -> 读日志直到退出 -> 非主动停止则退避后重启。
     * 整个循环属于同一个 job，重启不会额外生出并行的监管协程。
     */
    private suspend fun superviseProcess(ownGeneration: Int) {
        var restartDelayMs = INITIAL_RESTART_DELAY_MS
        cleanupStaleProcesses()
        while (currentCoroutineContext().isActive && !shouldStayStopped(ownGeneration)) {
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

    // 跑一轮 MaiBot：启动 -> 把输出逐行写进终端日志 -> 等它退出。阻塞调用，只在监管协程（IO 线程）里执行。
    private fun runProcessOnce(ownGeneration: Int) {
        var startedProcess: Process? = null
        try {
            val script = MaiBotScripts.launch()
            appendLog("$LOG_PREFIX Starting: ${commandRunner.commandLine(script).joinToString(" ")}")

            val currentProcess = commandRunner.start(script, MaiBotAgreement.environment(paths))
            startedProcess = currentProcess
            process = currentProcess
            // 进程刚起来时若恰好收到停止请求，stopLocked 可能还没看到这个进程，这里自行结束它。
            if (shouldStayStopped(ownGeneration)) {
                terminate(currentProcess)
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
        withContext(Dispatchers.IO) { terminate(currentProcess) }
        process = null
    }

    // 先 SIGTERM 给 MaiBot 3 秒收尾，仍未退出再强杀，强杀时在终端里记一笔。
    private fun terminate(target: Process) {
        if (target.terminateGracefully()) {
            appendLog("$LOG_PREFIX Force-killed process that ignored SIGTERM")
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
 */
internal data class MaiBotProcessStatus(
    val running: Boolean = false,
    val exitCount: Int = 0,
    val lastExitCode: Int? = null,
    val launchBlockedReason: String? = null
)
