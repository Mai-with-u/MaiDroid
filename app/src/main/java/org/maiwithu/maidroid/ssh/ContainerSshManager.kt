package org.maiwithu.maidroid.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val errorMessage: String?
) {
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
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var process: Process? = null
    private var job: Job? = null
    private var intentionallyStopped = false

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
                errorMessage = null
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
        if (shuttingDown || job?.isActive == true || process?.isAlive == true) return

        val port = settings.containerSshPort
        val password = settings.containerSshRootPassword
        if (!settings.containerSshConfigured || password.isBlank()) {
            fail("Container SSH is not configured")
            return
        }

        intentionallyStopped = false
        job = scope.launch {
            _state.update {
                readState(phase = ContainerSshPhase.Starting, errorMessage = null)
            }

            val validationError = withContext(Dispatchers.IO) { readiness.containerProblem() }
            if (validationError != null) {
                fail(validationError)
                return@launch
            }

            TerminalLog.append("")
            TerminalLog.append("[ContainerSSH] configuring sshd on port $port")
            val setupResult = runCommand(ContainerSshScripts.configure(port, password))
            if (!setupResult.success) {
                fail(setupResult.summary())
                return@launch
            }

            runCommand(ContainerSshScripts.cleanup())

            TerminalLog.append("[ContainerSSH] starting sshd on port $port")
            withContext(Dispatchers.IO) {
                try {
                    val currentProcess = commandRunner.start(ContainerSshScripts.launch(port))
                    process = currentProcess
                    _state.update {
                        readState(phase = ContainerSshPhase.Running, errorMessage = null)
                    }

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
                    process = null
                    val nextPhase = if (intentionallyStopped) {
                        ContainerSshPhase.Idle
                    } else {
                        ContainerSshPhase.Failed
                    }
                    _state.update {
                        readState(
                            phase = nextPhase,
                            errorMessage = if (nextPhase == ContainerSshPhase.Failed) {
                                "sshd exited with code: $exitCode"
                            } else {
                                null
                            }
                        )
                    }
                } catch (e: Exception) {
                    process = null
                    if (!intentionallyStopped) {
                        fail(e.message.orEmpty().ifBlank { "Failed to start sshd" })
                    }
                }
            }
        }
    }

    fun stop() {
        intentionallyStopped = true
        job?.cancel()
        job = null
        scope.launch {
            stopRunningProcess()
            _state.update { readState(ContainerSshPhase.Idle, errorMessage = null) }
        }
    }

    /**
     * 安全退出：拦下之后的启动 -> 停掉 sshd 并清理残留，挂起到停完。
     * sshd 没有要保存的数据，照常结束即可；SSH 开关的设置不动，下次打开 MaiDroid 照旧按设置启动。
     */
    suspend fun stopForExit(): ProcessExitOutcome = withContext(Dispatchers.Main.immediate) {
        shuttingDown = true
        val wasRunning = job?.isActive == true || process?.isAlive == true
        intentionallyStopped = true
        job?.cancel()
        job = null
        stopRunningProcess()
        _state.update { readState(ContainerSshPhase.Idle, errorMessage = null) }
        if (wasRunning) ProcessExitOutcome.Exited else ProcessExitOutcome.NotRunning
    }

    private fun restart() {
        intentionallyStopped = true
        job?.cancel()
        job = null
        scope.launch {
            stopRunningProcess()
            intentionallyStopped = false
            start()
        }
    }

    // 强杀 sshd 的 proot，再按进程名清掉脱离出来的 sshd：sshd 没有要保存的数据，不用先请它退出。
    // 以前先给 proot 发 SIGTERM 再等 3 秒，但 proot 会忽略 SIGTERM，只是白等。
    private suspend fun stopRunningProcess() {
        withContext(Dispatchers.IO) {
            process?.killProot()
            process = null
            runCommand(ContainerSshScripts.cleanup())
        }
    }

    // sshd 配置与清理命令的输出统一加 [ContainerSSH] 前缀写进终端日志。
    private suspend fun runCommand(script: ContainerScript): ContainerCommandResult =
        commandRunner.run(script) { line ->
            if (line.isNotBlank()) {
                TerminalLog.append("[ContainerSSH] $line")
            }
        }

    private fun fail(message: String) {
        TerminalLog.append("[ContainerSSH] failed: $message")
        _state.update {
            readState(
                phase = ContainerSshPhase.Failed,
                errorMessage = message
            )
        }
    }

    private fun readState(
        phase: ContainerSshPhase,
        errorMessage: String? = null
    ): ContainerSshState =
        ContainerSshState(
            enabled = settings.containerSshEnabled,
            configured = settings.containerSshConfigured,
            port = settings.containerSshPort,
            phase = phase,
            errorMessage = errorMessage
        )
}
