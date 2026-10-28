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
import org.maiwithu.maidroid.log.TerminalLog
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import java.util.concurrent.TimeUnit

internal enum class ContainerSshPhase {
    Idle,
    Starting,
    Running,
    Failed
}

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
        if (job?.isActive == true || process?.isAlive == true) return

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

    private suspend fun stopRunningProcess() {
        withContext(Dispatchers.IO) {
            process?.let { currentProcess ->
                runCatching {
                    currentProcess.destroy()
                    currentProcess.waitFor(3, TimeUnit.SECONDS)
                    if (currentProcess.isAlive) {
                        currentProcess.destroyForcibly()
                    }
                }
            }
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
