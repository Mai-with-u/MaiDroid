package org.maiwithu.maidroid.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.diagnostics.failureOf
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ProcessExitOutcome
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.runtime.killProot
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.terminal.TerminalLog

/** 容器 sshd 的运行阶段。 */
internal enum class ContainerSshPhase {
    Idle,
    Starting,
    Running,
    Failed
}

/** 设置页“容器 SSH”一行展示的状态：是否开启、是否配置过、端口、运行阶段和失败原因。 */
internal data class ContainerSshState(
    val enabled: Boolean,
    val configured: Boolean,
    val port: Int,
    val phase: ContainerSshPhase,
    /** 启动失败时带错误码的原因。 */
    val failure: Failure?
) {
    val errorMessage: String? get() = failure?.message
    val isStarting: Boolean = phase == ContainerSshPhase.Starting
    val isRunning: Boolean = phase == ContainerSshPhase.Running
}

/**
 * 容器里的 sshd：按设置启停、保存端口和 root 密码，并把运行阶段暴露给设置页。
 * 启动顺序：检查容器 -> 配置 sshd（必要时 apt 安装）-> 清理残留 sshd -> 前台运行 sshd。
 */
internal class ContainerSshManager(
    private val settings: AppSettings,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner
) {
    private companion object {
        const val STOP_JOIN_TIMEOUT_MS = 5_000L
    }

    private val scope = backgroundScope("容器 SSH", Dispatchers.Main.immediate)

    // 串行化启停、配置安装与残留清理阶段，避免用户启用后立即保存配置导致两轮 configure/cleanup 并发互杀。
    private val lifecycleMutex = Mutex()

    private var process: Process? = null
    private var monitorJob: Job? = null
    private var intentionallyStopped = false
    // 每次停止、重启或退出递增代数，让仍在跑 configure 的旧轮次感知取消后自动收手，不再起过期 sshd。
    private var configGeneration = 0L

    // 安全退出开始后永久拦下启动请求；退出完成时整个应用进程都会结束，不需要复位。和上面几个字段一样只在主线程读写。
    private var shuttingDown = false

    private val _state = MutableStateFlow(readState(ContainerSshPhase.Idle))
    val state: StateFlow<ContainerSshState> = _state.asStateFlow()

    fun saveConfig(port: Int, rootPassword: String?) {
        val passwordToSave = rootPassword ?: settings.containerSshRootPassword
        settings.saveContainerSsh(port, passwordToSave)
        _state.update {
            readState(
                phase = when {
                    process?.isAlive == true -> ContainerSshPhase.Running
                    it.isStarting -> ContainerSshPhase.Starting
                    else -> ContainerSshPhase.Idle
                },
                failure = null
            )
        }
        if (settings.containerSshEnabled) {
            restart()
        }
    }

    fun setEnabled(enabled: Boolean) {
        settings.containerSshEnabled = enabled
        _state.update { readState(if (enabled) it.phase else ContainerSshPhase.Idle) }
        if (enabled) {
            intentionallyStopped = false
            syncWithSettings()
        } else {
            stop()
        }
    }

    fun syncWithSettings() {
        if (settings.containerSshEnabled) {
            start()
        } else {
            stop()
        }
    }

    fun start() {
        if (shuttingDown) return
        // 不在锁外改动 intentionallyStopped，确保紧接在 restart/stop 之后的 sync 启动按队列安全进入 startLocked。
        scope.launch {
            lifecycleMutex.withLock {
                if (!shuttingDown && settings.containerSshEnabled) {
                    intentionallyStopped = false
                    startLocked()
                }
            }
        }
    }

    // 必须在 lifecycleMutex 内且主线程上下文调用；拉起 sshd 并把 waitFor 交给 monitorJob 后即释放锁，避免阻塞 stop/exit。
    private suspend fun startLocked() {
        if (shuttingDown || intentionallyStopped || !settings.containerSshEnabled ||
            process?.isAlive == true || monitorJob?.isActive == true
        ) {
            return
        }

        val port = settings.containerSshPort
        val password = settings.containerSshRootPassword
        if (!settings.containerSshConfigured || password.isBlank()) {
            fail(Failure(FailureStep.SshConfig, "还没有设置端口和 root 密码"))
            return
        }

        val startGen = configGeneration
        _state.update {
            readState(phase = ContainerSshPhase.Starting, failure = null)
        }

        val validationError = withContext(Dispatchers.IO) { readiness.containerProblem() }
        if (isAborted(startGen)) return
        if (validationError != null) {
            fail(Failure(FailureStep.SshSetup, validationError))
            return
        }

        TerminalLog.append("")
        TerminalLog.append("[ContainerSSH] configuring sshd on port $port")
        val setupResult = runCommand(ContainerSshScripts.configure(port, password))
        if (isAborted(startGen)) return
        if (!setupResult.success) {
            fail(failureOf(FailureStep.SshSetup, setupResult.summary(), setupResult.output))
            return
        }

        runCommand(ContainerSshScripts.cleanup())
        if (isAborted(startGen)) return

        TerminalLog.append("[ContainerSSH] starting sshd on port $port")
        val currentProcess = try {
            withContext(Dispatchers.IO) { commandRunner.start(ContainerSshScripts.launch(port)) }
        } catch (e: Exception) {
            if (!isAborted(startGen)) {
                fail(failureOf(FailureStep.SshStart, e.message.orEmpty().ifBlank { "sshd 没能启动" }))
            }
            return
        }
        if (isAborted(startGen)) {
            withContext(Dispatchers.IO) { currentProcess.killProot() }
            return
        }

        process = currentProcess
        _state.update {
            readState(phase = ContainerSshPhase.Running, failure = null)
        }
        monitorJob = scope.launch(Dispatchers.IO) { monitorProcess(currentProcess) }
    }

    private fun isAborted(startGen: Long): Boolean =
        shuttingDown || intentionallyStopped || !settings.containerSshEnabled || startGen != configGeneration

    // 在 IO 线程阻塞读取 sshd 输出与退出码，切回主线程时仅在仍持有同一进程引用时更新状态，防止旧轮次退出覆盖新态。
    private suspend fun monitorProcess(currentProcess: Process) {
        try {
            currentProcess.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trimEnd('\r')
                    if (line.isNotBlank()) {
                        TerminalLog.append("[ContainerSSH] $line")
                    }
                }
            }
            val exitCode = currentProcess.waitFor()
            TerminalLog.append("[ContainerSSH] sshd exited with code: $exitCode")
            withContext(Dispatchers.Main.immediate) {
                if (process !== currentProcess) return@withContext
                process = null
                monitorJob = null
                val nextPhase = if (intentionallyStopped || shuttingDown) {
                    ContainerSshPhase.Idle
                } else {
                    ContainerSshPhase.Failed
                }
                _state.update {
                    readState(
                        phase = nextPhase,
                        failure = if (nextPhase == ContainerSshPhase.Failed) {
                            Failure(FailureStep.SshStart, "sshd 意外退出，退出码 $exitCode").also { failure ->
                                TerminalLog.append(failure.logLine())
                            }
                        } else {
                            null
                        }
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            withContext(Dispatchers.Main.immediate) {
                if (process !== currentProcess) return@withContext
                process = null
                monitorJob = null
                if (!intentionallyStopped && !shuttingDown) {
                    fail(failureOf(FailureStep.SshStart, e.message.orEmpty().ifBlank { "sshd 没能启动" }))
                }
            }
        }
    }

    fun stop() {
        intentionallyStopped = true
        configGeneration++
        scope.launch {
            lifecycleMutex.withLock {
                stopLocked()
                _state.update { readState(ContainerSshPhase.Idle, failure = null) }
            }
        }
    }

    /**
     * 安全退出：拦下之后的启动 -> 停掉 sshd 并清理残留，挂起到停完。
     * sshd 没有要保存的数据，照常结束即可；SSH 开关的设置不动，下次打开 MaiDroid 照旧按设置启动。
     */
    suspend fun stopForExit(): ProcessExitOutcome = withContext(Dispatchers.Main.immediate) {
        shuttingDown = true
        intentionallyStopped = true
        configGeneration++
        lifecycleMutex.withLock {
            val wasRunning = process?.isAlive == true || monitorJob?.isActive == true
            stopLocked()
            _state.update { readState(ContainerSshPhase.Idle, failure = null) }
            if (wasRunning) ProcessExitOutcome.Exited else ProcessExitOutcome.NotRunning
        }
    }

    private fun restart() {
        if (shuttingDown) return
        intentionallyStopped = true
        configGeneration++
        scope.launch {
            lifecycleMutex.withLock {
                stopLocked()
                if (!shuttingDown && settings.containerSshEnabled) {
                    intentionallyStopped = false
                    startLocked()
                } else {
                    _state.update { readState(ContainerSshPhase.Idle, failure = null) }
                }
            }
        }
    }

    // 在 lifecycleMutex 内执行：先摘除引用并强杀旧 proot 与残留 sshd，再等待读日志监控协程收尾。
    private suspend fun stopLocked() {
        val currentProcess = process
        process = null
        val runningMonitor = monitorJob
        monitorJob = null
        withContext(Dispatchers.IO) {
            currentProcess?.killProot()
            runCommand(ContainerSshScripts.cleanup())
        }
        withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { runningMonitor?.join() }
    }

    // sshd 配置与清理命令的输出统一加 [ContainerSSH] 前缀写进终端日志。
    private suspend fun runCommand(script: ContainerScript): ContainerCommandResult =
        commandRunner.run(script) { line ->
            if (line.isNotBlank()) {
                TerminalLog.append("[ContainerSSH] $line")
            }
        }

    private fun fail(failure: Failure) {
        TerminalLog.append("[ContainerSSH] failed: ${failure.message}")
        TerminalLog.append(failure.logLine())
        _state.update {
            readState(
                phase = ContainerSshPhase.Failed,
                failure = failure
            )
        }
    }

    private fun readState(
        phase: ContainerSshPhase,
        failure: Failure? = null
    ): ContainerSshState =
        ContainerSshState(
            enabled = settings.containerSshEnabled,
            configured = settings.containerSshConfigured,
            port = settings.containerSshPort,
            phase = phase,
            failure = failure
        )
}
