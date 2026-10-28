package org.maiwithu.maidroid.ssh

import android.content.Context
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
import org.maiwithu.maidroid.process.TerminalLogRepository
import org.maiwithu.maidroid.repository.SettingsRepository
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimePaths
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

internal class ContainerSshManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: ContainerSshManager? = null

        fun get(context: Context): ContainerSshManager =
            instance ?: synchronized(this) {
                instance ?: ContainerSshManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val settingsRepository = SettingsRepository()
    private val config = RuntimePaths.from(appContext)
    private val commandRunner = ContainerCommandRunner(config)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var process: Process? = null
    private var job: Job? = null
    private var intentionallyStopped = false

    private val _state = MutableStateFlow(readState(ContainerSshPhase.Idle))
    val state: StateFlow<ContainerSshState> = _state.asStateFlow()

    fun saveConfig(port: Int, rootPassword: String?) {
        val passwordToSave = rootPassword ?: settingsRepository.getContainerSshRootPassword()
        settingsRepository.setContainerSshConfig(port, passwordToSave)
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
        if (settingsRepository.isContainerSshEnabled()) {
            restart()
        }
    }

    fun setEnabled(enabled: Boolean) {
        settingsRepository.setContainerSshEnabled(enabled)
        _state.update { readState(if (enabled) it.phase else ContainerSshPhase.Idle) }
        if (enabled) {
            syncWithSettings()
        } else {
            stop()
        }
    }

    fun syncWithSettings() {
        if (settingsRepository.isContainerSshEnabled()) {
            start()
        } else {
            stop()
        }
    }

    fun start() {
        if (job?.isActive == true || process?.isAlive == true) return

        val port = settingsRepository.getContainerSshPort()
        val password = settingsRepository.getContainerSshRootPassword()
        if (!settingsRepository.isContainerSshConfigured() || password.isBlank()) {
            fail("Container SSH is not configured")
            return
        }

        intentionallyStopped = false
        job = scope.launch {
            _state.update {
                readState(phase = ContainerSshPhase.Starting, errorMessage = null)
            }

            val validationError = withContext(Dispatchers.IO) {
                RuntimeFileModes.ensureExecutableBits(config)
                config.containerRequirements()
                    .firstOrNull { !it.ready }
                    ?.let { "${it.name} is not ready: ${it.hint}" }
            }
            if (validationError != null) {
                fail(validationError)
                return@launch
            }

            TerminalLogRepository.append("")
            TerminalLogRepository.append("[ContainerSSH] configuring sshd on port $port")
            val setupResult = runCommand(ContainerSshScripts.configure(port, password))
            if (!setupResult.success) {
                fail(setupResult.summary())
                return@launch
            }

            runCommand(ContainerSshScripts.cleanup())

            TerminalLogRepository.append("[ContainerSSH] starting sshd on port $port")
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
                                TerminalLogRepository.append("[ContainerSSH] $line")
                            }
                        }
                    }
                    val exitCode = currentProcess.waitFor()
                    TerminalLogRepository.append("[ContainerSSH] sshd exited with code: $exitCode")
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
                TerminalLogRepository.append("[ContainerSSH] $line")
            }
        }

    private fun fail(message: String) {
        TerminalLogRepository.append("[ContainerSSH] failed: $message")
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
            enabled = settingsRepository.isContainerSshEnabled(),
            configured = settingsRepository.isContainerSshConfigured(),
            port = settingsRepository.getContainerSshPort(),
            phase = phase,
            errorMessage = errorMessage
        )
}
