package org.maiwithu.maidroid.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maiwithu.maidroid.container.MaiBotContainerConfig
import org.maiwithu.maidroid.process.TerminalLogRepository
import org.maiwithu.maidroid.service.ChatbotService
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

internal enum class NapCatInstallPhase {
    Idle,
    Installing,
    Installed,
    Failed
}

internal data class NapCatPlatformState(
    val phase: NapCatInstallPhase,
    val installed: Boolean,
    val progress: Float,
    val progressDescription: String,
    val errorMessage: String?,
    val qrCodePath: String?,
    val qrCodeLastModified: Long,
    val oneBotReachable: Boolean,
    val oneBotConfig: NapCatOneBotConfig?,
    val accountId: String?,
    val accountName: String?
) {
    val isInstalling: Boolean = phase == NapCatInstallPhase.Installing
    val hasQrCode: Boolean = qrCodePath != null && qrCodeLastModified > 0L
    val canOpenSettings: Boolean = installed || hasQrCode
    val accountDisplayName: String? = accountName?.ifBlank { null } ?: accountId

    companion object {
        fun initial(installed: Boolean, qrCodePath: String?, qrCodeLastModified: Long) =
            NapCatPlatformState(
                phase = if (installed) NapCatInstallPhase.Installed else NapCatInstallPhase.Idle,
                installed = installed,
                progress = if (installed) 1f else 0f,
                progressDescription = if (installed) "NapCat 已安装" else "等待安装",
                errorMessage = null,
                qrCodePath = qrCodePath,
                qrCodeLastModified = qrCodeLastModified,
                oneBotReachable = false,
                oneBotConfig = null,
                accountId = null,
                accountName = null
            )
    }
}

internal data class NapCatOneBotConfig(
    val serviceType: String,
    val name: String,
    val enabled: Boolean,
    val host: String,
    val port: Int?,
    val token: String,
    val messagePostFormat: String,
    val reportSelfMessage: Boolean?,
    val enableForcePushEvent: Boolean?,
    val debug: Boolean?,
    val heartInterval: Int?,
    val configPath: String
) {
    val endpoint: String = if (port != null) "$host:$port" else host
}

internal class NapCatPlatformManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: NapCatPlatformManager? = null

        fun get(context: Context): NapCatPlatformManager =
            instance ?: synchronized(this) {
                instance ?: NapCatPlatformManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val config = MaiBotContainerConfig.from(appContext)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }
    private var installJob: Job? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(readInitialState())
    val state: StateFlow<NapCatPlatformState> = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refreshRuntimeState()
                delay(2_000L)
            }
        }
    }

    fun refresh() {
        scope.launch {
            refreshRuntimeState()
        }
    }

    fun installNapCat(targetProxy: String? = null) {
        if (installJob?.isActive == true) return

        if (config.isNapCatInstalled()) {
            refresh()
            restartRuntime()
            return
        }

        installJob = scope.launch {
            _state.update {
                it.copy(
                    phase = NapCatInstallPhase.Installing,
                    installed = false,
                    progress = 0.02f,
                    progressDescription = "准备安装 NapCat",
                    errorMessage = null
                )
            }

            val validationError = withContext(Dispatchers.IO) {
                config.ensureExecutableBits()
                config.containerRequirements()
                    .firstOrNull { !it.ready }
                    ?.let { "${it.name} 未就绪：${it.hint}" }
                    ?: if (!config.isSourceReady()) {
                        "MaiBot 源码尚未安装，无法配置 NapCat 适配器"
                    } else {
                        null
                    }
            }
            if (validationError != null) {
                fail(validationError)
                return@launch
            }

            clearProgressFiles()
            startProgressWatcher()

            val command = config.napCatInstallCommand(
                token = generateToken(),
                targetProxy = targetProxy?.takeIf { it.isNotBlank() }
            )
            TerminalLogRepository.append("")
            TerminalLogRepository.append("[NapCat] 启动安装流程")

            val result = runCommand(command)
            progressJob?.cancel()
            progressJob = null

            if (result.success && config.isNapCatInstalled()) {
                val qr = readQrCode()
                _state.update {
                    it.copy(
                        phase = NapCatInstallPhase.Installed,
                        installed = true,
                        progress = 1f,
                        progressDescription = "NapCat 安装和自动配置完成",
                        errorMessage = null,
                        qrCodePath = qr.path,
                        qrCodeLastModified = qr.lastModified
                    )
                }
                TerminalLogRepository.append("[NapCat] 安装完成，正在启动 NapCat 并重启 MaiBot 以加载适配器")
                restartRuntime()
            } else {
                fail(result.shortMessage())
            }
        }
    }

    private fun restartRuntime() {
        val intent = Intent(appContext, ChatbotService::class.java).apply {
            action = ChatbotService.ACTION_RESTART_RUNTIME
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appContext.startForegroundService(intent)
        } else {
            appContext.startService(intent)
        }
    }

    private suspend fun runCommand(command: List<String>): CommandResult =
        withContext(Dispatchers.IO) {
            try {
                val processBuilder = ProcessBuilder(command)
                    .directory(config.rootfsDir)
                    .redirectErrorStream(true)
                processBuilder.environment().putAll(config.prootEnvironment())
                processBuilder.environment().putAll(config.agreementEnvironment())

                val process = processBuilder.start()
                val outputBuilder = StringBuilder()
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { rawLine ->
                            val line = rawLine.trimEnd('\r')
                            outputBuilder.appendLine(line)
                            TerminalLogRepository.append(line)
                        }
                    }
                    val exitCode = process.waitFor()
                    TerminalLogRepository.append("[NapCat] 安装流程退出码: $exitCode")
                    CommandResult(exitCode = exitCode, output = outputBuilder.toString())
                } finally {
                    if (process.isAlive) {
                        process.destroy()
                    }
                }
            } catch (e: Exception) {
                TerminalLogRepository.append("[NapCat] 安装异常: ${e.message.orEmpty()}")
                CommandResult(exitCode = -1, output = e.message.orEmpty())
            }
        }

    private fun startProgressWatcher() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                val progress = withContext(Dispatchers.IO) { readProgressFiles() }
                if (progress != null) {
                    _state.update {
                        it.copy(
                            phase = NapCatInstallPhase.Installing,
                            progress = progress.value,
                            progressDescription = progress.description.ifBlank {
                                it.progressDescription
                            }
                        )
                    }
                }
                delay(500L)
            }
        }
    }

    private fun clearProgressFiles() {
        runCatching { config.termuxTmpDir.mkdirs() }
        runCatching { config.termuxTmpDir.resolve("progress").delete() }
        runCatching { config.termuxTmpDir.resolve("progress_des").delete() }
    }

    private fun readProgressFiles(): InstallProgress? {
        val progressFile = config.termuxTmpDir.resolve("progress")
        val descriptionFile = config.termuxTmpDir.resolve("progress_des")
        if (!progressFile.isFile && !descriptionFile.isFile) return null

        val rawProgress = progressFile.takeIf { it.isFile }
            ?.readText()
            ?.trim()
            ?.toFloatOrNull()
        val normalizedProgress = rawProgress
            ?.let { if (it > 1f) it / 100f else it }
            ?.coerceIn(0f, 1f)
            ?: _state.value.progress
        val description = descriptionFile.takeIf { it.isFile }
            ?.readText()
            ?.trim()
            .orEmpty()
        return InstallProgress(value = normalizedProgress, description = description)
    }

    private suspend fun refreshRuntimeState() {
        val runtimeState = withContext(Dispatchers.IO) { readRuntimeState() }
        _state.update { current ->
            current.copy(
                phase = when {
                    current.isInstalling -> NapCatInstallPhase.Installing
                    runtimeState.installed -> NapCatInstallPhase.Installed
                    current.phase == NapCatInstallPhase.Failed -> NapCatInstallPhase.Failed
                    else -> NapCatInstallPhase.Idle
                },
                progress = if (runtimeState.installed && !current.isInstalling) 1f else current.progress,
                progressDescription = if (runtimeState.installed && !current.isInstalling) {
                    "NapCat 已安装"
                } else {
                    current.progressDescription
                },
                qrCodePath = runtimeState.qrCode.path,
                qrCodeLastModified = runtimeState.qrCode.lastModified,
                installed = runtimeState.installed,
                oneBotReachable = runtimeState.oneBotReachable,
                oneBotConfig = runtimeState.oneBotConfig,
                accountId = runtimeState.accountId,
                accountName = runtimeState.accountName
            )
        }
    }

    private fun readInitialState(): NapCatPlatformState {
        val runtimeState = readRuntimeState(checkConnection = false)
        return NapCatPlatformState.initial(
            installed = runtimeState.installed,
            qrCodePath = runtimeState.qrCode.path,
            qrCodeLastModified = runtimeState.qrCode.lastModified
        ).copy(
            oneBotReachable = runtimeState.oneBotReachable,
            oneBotConfig = runtimeState.oneBotConfig,
            accountId = runtimeState.accountId,
            accountName = runtimeState.accountName
        )
    }

    private fun readRuntimeState(checkConnection: Boolean = true): RuntimeState {
        val installed = config.isNapCatInstalled()
        val qr = readQrCode()
        val oneBotConfig = readOneBotConfig()
        val accountId = readAccountId(oneBotConfig)
        return RuntimeState(
            installed = installed,
            qrCode = qr,
            oneBotReachable = if (checkConnection) {
                oneBotConfig?.let(::isOneBotReachable) ?: false
            } else {
                false
            },
            oneBotConfig = oneBotConfig,
            accountId = accountId,
            accountName = null
        )
    }

    private fun readQrCode(): QrCodeState {
        val file = config.napCatQrCodeFile
        return if (file.isFile) {
            QrCodeState(path = file.absolutePath, lastModified = file.lastModified())
        } else {
            QrCodeState(path = null, lastModified = 0L)
        }
    }

    private fun readOneBotConfig(): NapCatOneBotConfig? {
        val file = findOneBotConfigFile() ?: return null

        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val network = root["network"]?.jsonObject
            val server = network
                ?.get("websocketServers")
                ?.jsonArray
                ?.mapNotNull { runCatching { it.jsonObject }.getOrNull() }
                ?.let { servers ->
                    servers.firstOrNull {
                        it["enable"]?.jsonPrimitive?.booleanOrNull == true
                    } ?: servers.firstOrNull()
                }
                ?: return null

            NapCatOneBotConfig(
                serviceType = "WebSocket Server",
                name = server["name"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "WsServer" },
                enabled = server["enable"]?.jsonPrimitive?.booleanOrNull ?: false,
                host = server["host"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "127.0.0.1" },
                port = server["port"]?.jsonPrimitive?.intOrNull,
                token = server["token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                messagePostFormat = server["messagePostFormat"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reportSelfMessage = server["reportSelfMessage"]?.jsonPrimitive?.booleanOrNull,
                enableForcePushEvent = server["enableForcePushEvent"]?.jsonPrimitive?.booleanOrNull,
                debug = server["debug"]?.jsonPrimitive?.booleanOrNull,
                heartInterval = server["heartInterval"]?.jsonPrimitive?.intOrNull,
                configPath = "/root/napcat/config/${file.name}"
            )
        }.getOrNull()
    }

    private fun findOneBotConfigFile(): java.io.File? {
        val accountConfig = config.napCatConfigDir.listFiles()
            ?.filter { it.isFile && Regex("""onebot11_\d+\.json""").matches(it.name) }
            ?.maxByOrNull { it.lastModified() }
        return accountConfig ?: config.napCatOneBotConfig.takeIf { it.isFile }
    }

    private fun readAccountId(oneBotConfig: NapCatOneBotConfig?): String? {
        val fromConfig = oneBotConfig
            ?.configPath
            ?.let { Regex("""onebot11_(\d+)\.json""").find(it)?.groupValues?.getOrNull(1) }
        if (!fromConfig.isNullOrBlank()) return fromConfig

        val loginDir = java.io.File(
            config.rootfsDir,
            "root/.config/QQ/nt_qq/global/nt_data/Login"
        )
        return loginDir.listFiles()
            ?.asSequence()
            ?.mapNotNull { Regex("""\.(\d+)""").matchEntire(it.name)?.groupValues?.getOrNull(1) }
            ?.firstOrNull()
    }

    private fun isOneBotReachable(config: NapCatOneBotConfig): Boolean {
        val port = config.port ?: return false
        if (!config.enabled) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(config.host, port), 250)
            }
            true
        }.getOrDefault(false)
    }

    private fun fail(message: String) {
        _state.update {
            it.copy(
                phase = NapCatInstallPhase.Failed,
                installed = config.isNapCatInstalled(),
                progress = 0f,
                progressDescription = "NapCat 安装失败",
                errorMessage = message
            )
        }
        TerminalLogRepository.append("[NapCat] 安装失败: $message")
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class InstallProgress(
        val value: Float,
        val description: String
    )

    private data class QrCodeState(
        val path: String?,
        val lastModified: Long
    )

    private data class RuntimeState(
        val installed: Boolean,
        val qrCode: QrCodeState,
        val oneBotReachable: Boolean,
        val oneBotConfig: NapCatOneBotConfig?,
        val accountId: String?,
        val accountName: String?
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
                ?: "命令退出码: $exitCode"
    }
}

internal object NapCatRuntime {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var process: Process? = null
    private var job: Job? = null

    fun start(context: Context) {
        val appContext = context.applicationContext
        val config = MaiBotContainerConfig.from(appContext)
        if (process?.isAlive == true || job?.isActive == true) return
        if (!config.isNapCatInstalled()) return

        config.ensureExecutableBits()
        job = scope.launch {
            runMaintenanceCommand(config, config.cleanupNapCatCommand())
            runMaintenanceCommand(config, config.napCatPrepareRuntimeCommand())

            if (!config.napCatLauncher.isFile) {
                TerminalLogRepository.append("[NapCat] 未找到 /root/launcher.sh，暂不启动 NapCat")
                return@launch
            }

            val command = config.napCatLaunchCommand()
            TerminalLogRepository.append("")
            TerminalLogRepository.append("[NapCat] 启动: bash /root/launcher.sh")
            try {
                val processBuilder = ProcessBuilder(command)
                    .directory(config.rootfsDir)
                    .redirectErrorStream(true)
                processBuilder.environment().putAll(config.prootEnvironment())
                process = processBuilder.start()
                val currentProcess = process ?: return@launch
                currentProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { rawLine ->
                        TerminalLogRepository.append("[NapCat] ${rawLine.trimEnd('\r')}")
                    }
                }
                val exitCode = currentProcess.waitFor()
                TerminalLogRepository.append("[NapCat] 进程退出码: $exitCode")
            } catch (e: Exception) {
                TerminalLogRepository.append("[NapCat] 启动异常: ${e.message.orEmpty()}")
            } finally {
                process = null
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
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
    }

    fun stop(context: Context) {
        stop()
        val config = MaiBotContainerConfig.from(context.applicationContext)
        scope.launch {
            runMaintenanceCommand(config, config.cleanupNapCatCommand())
        }
    }

    private suspend fun runMaintenanceCommand(
        config: MaiBotContainerConfig,
        command: List<String>
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                val processBuilder = ProcessBuilder(command)
                    .directory(config.rootfsDir)
                    .redirectErrorStream(true)
                processBuilder.environment().putAll(config.prootEnvironment())
                val maintenanceProcess = processBuilder.start()
                maintenanceProcess.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isNotBlank()) {
                            TerminalLogRepository.append(line.trimEnd('\r'))
                        }
                    }
                }
                val exitCode = maintenanceProcess.waitFor()
                if (exitCode != 0) {
                    TerminalLogRepository.append("[NapCat] maintenance command exited with code: $exitCode")
                }
            }.onFailure { error ->
                TerminalLogRepository.append("[NapCat] maintenance command failed: ${error.message.orEmpty()}")
            }
        }
    }
}
