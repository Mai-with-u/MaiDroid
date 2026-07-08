package org.maiwithu.maidroid.container

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
    private val config = MaiBotContainerConfig.from(appContext)
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
                config.ensureExecutableBits()
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
            val setupResult = runCommand(config.configureContainerSshCommand(port, password))
            if (!setupResult.success) {
                fail(setupResult.shortMessage())
                return@launch
            }

            runCommand(config.cleanupContainerSshCommand())

            val command = config.launchContainerSshCommand(port)
            TerminalLogRepository.append("[ContainerSSH] starting sshd on port $port")
            withContext(Dispatchers.IO) {
                try {
                    val processBuilder = ProcessBuilder(command)
                        .directory(config.rootfsDir)
                        .redirectErrorStream(true)
                    processBuilder.environment().putAll(config.prootEnvironment())

                    process = processBuilder.start()
                    _state.update {
                        readState(phase = ContainerSshPhase.Running, errorMessage = null)
                    }

                    val currentProcess = process ?: return@withContext
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
            runCommand(config.cleanupContainerSshCommand())
        }
    }

    private suspend fun runCommand(command: List<String>): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val processBuilder = ProcessBuilder(command)
                    .directory(config.rootfsDir)
                    .redirectErrorStream(true)
                processBuilder.environment().putAll(config.prootEnvironment())

                val commandProcess = processBuilder.start()
                val outputBuilder = StringBuilder()
                commandProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { rawLine ->
                        val line = rawLine.trimEnd('\r')
                        outputBuilder.appendLine(line)
                        if (line.isNotBlank()) {
                            TerminalLogRepository.append("[ContainerSSH] $line")
                        }
                    }
                }
                CommandResult(
                    exitCode = commandProcess.waitFor(),
                    output = outputBuilder.toString()
                )
            } catch (e: Exception) {
                CommandResult(exitCode = -1, output = e.message.orEmpty())
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

    private data class CommandResult(
        val exitCode: Int,
        val output: String
    ) {
        val success: Boolean = exitCode == 0

        fun shortMessage(): String =
            output.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.isNotEmpty() }
                ?: "command exited with code: $exitCode"
    }
}
