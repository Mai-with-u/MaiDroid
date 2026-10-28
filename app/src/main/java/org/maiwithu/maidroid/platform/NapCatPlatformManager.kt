package org.maiwithu.maidroid.platform

import android.content.Context
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.maiwithu.maidroid.container.ContainerCommandResult
import org.maiwithu.maidroid.container.ContainerCommandRunner
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
    val accountName: String?,
    // 以下三项只在二维码弹窗打开期间由 keepQrCodeFresh / refreshQrCode 维护。
    val loginPhase: String? = null,
    val qrRefreshing: Boolean = false,
    val qrRefreshError: String? = null
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

// 真机实测 QQ 登录二维码出码约 120 秒后失效，QQ 有时回 ErrType 1 / ErrCode 3，有时回 ErrCode 10。
// 当前 NapCat 只在 ErrCode 3 时自己重新出码，遇到 10 就一直停在旧码上，所以要由我们按码龄主动换。
// 门限比 120 秒多留 5 秒：ErrCode 3 时让 NapCat 先自己出码，免得我们紧跟着再换一次把新码作废。
internal const val NapCatQrCodeLifetimeMs = 125_000L
// 已扫码但迟迟没在手机上确认：再多等这么久仍未登录，说明这次扫码已作废（取消或超时）。
internal const val NapCatQrScannedGraceMs = 60_000L
// 刷新失败（QQ 登录服务重连中等）时不要连着打，至少隔这么久再自动重试。
internal const val NapCatQrRetryIntervalMs = 15_000L

/**
 * 二维码弹窗开着时，每轮轮询后判断要不要替用户换新码：
 * 已登录、登录后初始化中、登录服务重连中、正在出码 -> 不动，等 NapCat 自己推进；
 * 已扫码 -> 码龄超过有效期再加确认宽限仍未登录，才视为扫码作废并换码，避免打断正在手机上确认的用户；
 * 其余（等待扫码、离线）-> NapCat 报“过期”或码龄超过有效期就换；还没有码时码龄视为无穷大，直接要一张。
 */
internal fun shouldAutoRefreshQrCode(
    status: NapCatLoginStatus,
    qrAgeMs: Long,
    sinceLastAttemptMs: Long
): Boolean {
    if (status.isLogin) return false
    if (sinceLastAttemptMs < NapCatQrRetryIntervalMs) return false
    return when (status.phase) {
        "ready", "initializing", "reconnecting", "generating_qrcode" -> false
        "qrcode_scanned" -> qrAgeMs >= NapCatQrCodeLifetimeMs + NapCatQrScannedGraceMs
        else -> status.loginError.contains("过期") || qrAgeMs >= NapCatQrCodeLifetimeMs
    }
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
    private val commandRunner = ContainerCommandRunner(config)
    private val webUiClient = NapCatWebUiClient(config.napCatWebUiConfig)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }
    private var installJob: Job? = null
    private var progressJob: Job? = null
    private var lastQrRefreshAttemptAt = 0L

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

    /** 用户在二维码弹窗点“刷新”：让 NapCat 重新向 QQ 要一张新码，而不是只重读磁盘上的旧图。 */
    fun refreshQrCode() {
        scope.launch { requestNewQrCode() }
    }

    /**
     * 二维码弹窗打开期间运行，弹窗关闭时随调用方协程一起取消。
     * 每 3 秒：向 NapCat 问扫码进度 -> 同步到弹窗提示 -> 码已失效或扫码作废就自动换新码；
     * 新码由 NapCat 写回 qrcode.png，再经 2 秒一次的运行态刷新显示出来。
     */
    suspend fun keepQrCodeFresh() {
        try {
            while (currentCoroutineContext().isActive) {
                val status = webUiClient.loginStatus()
                if (status != null) {
                    _state.update { it.copy(loginPhase = status.phase) }
                    // 码龄直接看文件当前的修改时间：状态里的时间戳最多晚 2 秒，
                    // NapCat 刚自己换了码时会被误判成旧码过期而再换一次。
                    val qrModifiedAt = withContext(Dispatchers.IO) { config.napCatQrCodeFile.lastModified() }
                    val now = System.currentTimeMillis()
                    val qrAgeMs = now - qrModifiedAt
                    if (shouldAutoRefreshQrCode(status, qrAgeMs, now - lastQrRefreshAttemptAt)) {
                        TerminalLogRepository.append("[NapCat] 登录二维码已失效，自动请求新二维码")
                        // 刷新放到管理器自己的作用域里跑：用户中途关弹窗也能跑完并复位 qrRefreshing。
                        refreshQrCode()
                    }
                }
                delay(3_000L)
            }
        } finally {
            // 弹窗关了就不再展示扫码进度，下次打开重新查。
            _state.update { it.copy(loginPhase = null, qrRefreshError = null) }
        }
    }

    // 手动刷新和自动换码共用：同一时刻只发一个刷新请求，结果写回弹窗状态。
    // 全程跑在主线程调度器上，判断和置位 qrRefreshing 之间没有挂起点，不会并发重入。
    private suspend fun requestNewQrCode() {
        if (_state.value.qrRefreshing) return
        lastQrRefreshAttemptAt = System.currentTimeMillis()
        _state.update { it.copy(qrRefreshing = true, qrRefreshError = null) }
        val result = webUiClient.refreshQrCode()
        refreshRuntimeState()
        val error = result.exceptionOrNull()
        if (error != null) {
            TerminalLogRepository.append("[NapCat] 刷新二维码失败: ${error.message.orEmpty()}")
        }
        _state.update {
            it.copy(
                qrRefreshing = false,
                qrRefreshError = error?.let { e -> "刷新二维码失败：${e.message.orEmpty()}" }
            )
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
                fail(result.summary())
            }
        }
    }

    // NapCat 装好或已存在时，经前台服务重启运行时：NapCat 若未运行会被拉起，MaiBot 重启后加载适配器。
    private fun restartRuntime() {
        ChatbotService.start(appContext, ChatbotService.ACTION_RESTART_RUNTIME)
    }

    // 安装脚本需要读 MaiBot 目录下的协议文件来配置适配器，因此带上协议 hash 环境变量。
    private suspend fun runCommand(command: List<String>): ContainerCommandResult {
        val result = commandRunner.run(
            command = command,
            includeAgreement = true,
            onLine = TerminalLogRepository::append
        )
        TerminalLogRepository.append("[NapCat] 安装流程退出码: ${result.exitCode}")
        return result
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
                val currentProcess = ContainerCommandRunner(config).start(command)
                process = currentProcess
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

    // 清理残留进程、生成 launcher.sh 这类维护命令：只记非空输出，失败不中断后续启动。
    private suspend fun runMaintenanceCommand(
        config: MaiBotContainerConfig,
        command: List<String>
    ) {
        val result = ContainerCommandRunner(config).run(command) { line ->
            if (line.isNotBlank()) TerminalLogRepository.append(line)
        }
        if (!result.success) {
            TerminalLogRepository.append("[NapCat] maintenance command exited with code: ${result.exitCode}")
        }
    }
}
