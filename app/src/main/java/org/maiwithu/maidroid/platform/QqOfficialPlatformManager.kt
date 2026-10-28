package org.maiwithu.maidroid.platform

import android.content.Context
import android.os.SystemClock
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
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialAdapterFiles
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialAdapterScripts
import org.maiwithu.maidroid.process.ProcessManager
import org.maiwithu.maidroid.process.TerminalLogRepository
import org.maiwithu.maidroid.service.ChatbotService
import java.io.File

internal enum class QqOfficialInstallPhase {
    Idle,
    Installing,
    Installed,
    Failed
}

internal data class QqOfficialPlatformState(
    val phase: QqOfficialInstallPhase = QqOfficialInstallPhase.Idle,
    val installed: Boolean = false,
    val progress: Float = 0f,
    val progressDescription: String = "",
    // 安装失败的原因；连接失败另记在 connectionError。
    val errorMessage: String? = null,
    val credentials: QqOfficialCredentials = QqOfficialCredentials(),
    val maiBotRunning: Boolean = false,
    // 最近一段时间内适配器报的连接错误，过期或 MaiBot 重启后清空。
    val connectionError: String? = null
) {
    val isInstalling: Boolean = phase == QqOfficialInstallPhase.Installing
    val configured: Boolean = installed && credentials.configured
    // 适配器连上网关时不打日志，只能这样近似：已启用、MaiBot 在跑、近期没有连接报错。
    val presumedOnline: Boolean = configured && maiBotRunning && connectionError == null
}

/** 已安装卡片和配置弹窗里的一句话状态。 */
internal fun qqOfficialStatusText(state: QqOfficialPlatformState): String = when {
    state.isInstalling -> "正在安装"
    !state.installed -> "未安装"
    state.credentials.appId.isBlank() || state.credentials.appSecret.isBlank() -> "待填写 AppID"
    !state.credentials.enabled -> "插件已停用"
    !state.maiBotRunning -> "等待 MaiBot 启动"
    state.connectionError != null -> "连接失败"
    else -> "已启用"
}

internal sealed interface QqOfficialSaveResult {
    // 凭据和启用状态都没变，没有重启 MaiBot。
    data object Unchanged : QqOfficialSaveResult
    data object Restarting : QqOfficialSaveResult
    data class Failed(val message: String) : QqOfficialSaveResult
}

// 适配器连接失败时每隔 5 秒重试，单次请求最长 20 秒，所以一分钟内没有新报错就当作已经连上。
internal const val QqOfficialErrorStaleMs = 60_000L

/**
 * 管理 QQ 官方机器人适配器：安装、写凭据、汇总状态。
 *
 * 适配器是 MaiBot 的插件，跟着 MaiBot 进程跑，没有自己的进程；
 * 所以“装好、改凭据”之后都要重启 MaiBot 让插件按新配置加载。
 */
internal class QqOfficialPlatformManager private constructor(context: Context) {
    companion object {
        @Volatile
        private var instance: QqOfficialPlatformManager? = null

        fun get(context: Context): QqOfficialPlatformManager =
            instance ?: synchronized(this) {
                instance ?: QqOfficialPlatformManager(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val config = RuntimePaths.from(appContext)
    private val readiness = RuntimeReadiness(config)
    private val adapterFiles = QqOfficialAdapterFiles(config)
    private val commandRunner = ContainerCommandRunner(config)
    private val processManager = ProcessManager.get(appContext)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var installJob: Job? = null
    // 最近一次连接报错的时间（开机时钟），只在主线程读写。
    private var connectionErrorAt = 0L

    private val _state = MutableStateFlow(
        QqOfficialPlatformState(
            installed = adapterFiles.isInstalled(),
            credentials = readCredentials()
        ).let { it.copy(phase = if (it.installed) QqOfficialInstallPhase.Installed else QqOfficialInstallPhase.Idle) }
    )
    val state: StateFlow<QqOfficialPlatformState> = _state.asStateFlow()

    init {
        // 先翻一遍已有日志：页面打开前适配器可能已经在报错，不必等下一次重试才显示出来。
        TerminalLogRepository.logs.value.forEach(::onLogLine)
        scope.launch {
            TerminalLogRepository.lines.collect(::onLogLine)
        }
        scope.launch {
            processManager.isRunning.collect { running ->
                _state.update { it.copy(maiBotRunning = running) }
            }
        }
        // 用户也可能在 MaiBot WebUI 里改插件配置，定时重读文件，顺带让过期的连接报错消失。
        scope.launch {
            while (isActive) {
                refreshFileState()
                delay(3_000L)
            }
        }
    }

    fun refresh() {
        scope.launch { refreshFileState() }
    }

    /**
     * 首次安装：检查容器和 MaiBot 源码 -> 克隆适配器到 plugins/MaiBot-QQ-Adapter ->
     * 写入 AppID、AppSecret 并启用插件 -> 重启 MaiBot 加载插件。
     * 已经装过（包括用户从 MaiBot 插件市场装的那份）就跳过克隆，直接把凭据写进那份的配置。
     */
    fun installAndConfigure(appId: String, appSecret: String) {
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            updateInstallProgress(0.05f, "准备安装 QQ 官方机器人适配器")
            val validationError = withContext(Dispatchers.IO) {
                readiness.containerProblem()
                    ?: if (!readiness.isMaiBotSourceReady()) "MaiBot 源码尚未安装，无法安装 QQ 官方机器人适配器" else null
            }
            if (validationError != null) {
                fail(validationError)
                return@launch
            }

            TerminalLogRepository.append("")
            val existingDir = withContext(Dispatchers.IO) { adapterFiles.findInstalledDir() }
            if (existingDir != null) {
                TerminalLogRepository.append("[QQ 官方机器人] 适配器已在 ${existingDir.name} 目录，跳过下载，只写入凭据")
            } else {
                updateInstallProgress(0.3f, "下载 QQ 官方机器人适配器")
                TerminalLogRepository.append("[QQ 官方机器人] 开始安装适配器")
                val result = commandRunner.run(
                    script = QqOfficialAdapterScripts.install(),
                    onLine = TerminalLogRepository::append
                )
                val installed = withContext(Dispatchers.IO) { adapterFiles.isInstalled() }
                if (!result.success || !installed) {
                    fail(result.summary())
                    return@launch
                }
            }

            updateInstallProgress(0.9f, "写入 AppID 和 AppSecret")
            val writeError = writeCredentials(appId, appSecret)
            if (writeError != null) {
                fail("写入配置失败：$writeError")
                return@launch
            }

            _state.update {
                it.copy(
                    phase = QqOfficialInstallPhase.Installed,
                    progress = 1f,
                    progressDescription = "QQ 官方机器人适配器已安装",
                    errorMessage = null
                )
            }
            refreshFileState()
            TerminalLogRepository.append("[QQ 官方机器人] 安装完成，正在重启 MaiBot 以加载适配器")
            restartMaiBot()
        }
    }

    /**
     * 已安装后改凭据：凭据和启用状态都没变就什么也不做；
     * 否则改写 config.toml 的三项 -> 清掉旧的连接报错 -> 重启 MaiBot，让适配器按新凭据重连。
     */
    suspend fun saveCredentials(appId: String, appSecret: String): QqOfficialSaveResult {
        val current = _state.value.credentials
        if (current.enabled && current.appId == appId.trim() && current.appSecret == appSecret.trim()) {
            return QqOfficialSaveResult.Unchanged
        }
        writeCredentials(appId, appSecret)?.let { return QqOfficialSaveResult.Failed(it) }
        // 上次安装时写配置失败留下的提示，这次写成功了就不再显示。
        _state.update { it.copy(errorMessage = null) }
        refreshFileState()
        TerminalLogRepository.append("[QQ 官方机器人] 已更新 AppID 和 AppSecret，正在重启 MaiBot 让适配器重新连接")
        restartMaiBot()
        return QqOfficialSaveResult.Restarting
    }

    // 凭据由宿主侧直接写进容器里的 config.toml：先写同目录临时文件再改名，MaiBot 不会读到写了一半的配置。
    private suspend fun writeCredentials(appId: String, appSecret: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val target = adapterFiles.configFile()
                val existing = target.takeIf { it.isFile }?.readText()
                val content = QqOfficialAdapterConfig.write(existing, appId.trim(), appSecret.trim())
                val staging = File(target.parentFile, "${target.name}.maidroid.tmp")
                staging.writeText(content)
                if (!staging.renameTo(target)) {
                    staging.delete()
                    error("无法替换 ${target.name}")
                }
            }.exceptionOrNull()?.let { it.message.orEmpty().ifBlank { it.javaClass.simpleName } }
        }

    // 与 NapCat 安装后一致，经前台服务重启：MaiBot 没在跑时这一步也会把它拉起来。
    private fun restartMaiBot() {
        clearConnectionError()
        ChatbotService.start(appContext, ChatbotService.ACTION_RESTART_RUNTIME)
    }

    // MaiBot 重启或刚写过新凭据：之前的连接报错属于旧配置，不再显示。
    private fun clearConnectionError() {
        connectionErrorAt = 0L
        _state.update { it.copy(connectionError = null) }
    }

    private fun onLogLine(line: String) {
        if (isMaiBotStartLine(line)) {
            clearConnectionError()
            return
        }
        val error = qqOfficialConnectionError(line) ?: return
        connectionErrorAt = SystemClock.elapsedRealtime()
        _state.update { it.copy(connectionError = error) }
    }

    private suspend fun refreshFileState() {
        val installed = withContext(Dispatchers.IO) { adapterFiles.isInstalled() }
        val credentials = withContext(Dispatchers.IO) { readCredentials() }
        val errorExpired = connectionErrorAt > 0L &&
            SystemClock.elapsedRealtime() - connectionErrorAt > QqOfficialErrorStaleMs
        if (errorExpired) connectionErrorAt = 0L
        _state.update { current ->
            current.copy(
                phase = when {
                    current.isInstalling -> QqOfficialInstallPhase.Installing
                    installed -> QqOfficialInstallPhase.Installed
                    current.phase == QqOfficialInstallPhase.Failed -> QqOfficialInstallPhase.Failed
                    else -> QqOfficialInstallPhase.Idle
                },
                installed = installed,
                progress = if (installed && !current.isInstalling) 1f else current.progress,
                credentials = credentials,
                connectionError = if (errorExpired) null else current.connectionError
            )
        }
    }

    private fun readCredentials(): QqOfficialCredentials =
        runCatching {
            QqOfficialAdapterConfig.read(
                adapterFiles.configFile().takeIf { it.isFile }?.readText()
            )
        }.getOrDefault(QqOfficialCredentials())

    private fun updateInstallProgress(progress: Float, description: String) {
        _state.update {
            it.copy(
                phase = QqOfficialInstallPhase.Installing,
                progress = progress,
                progressDescription = description,
                errorMessage = null
            )
        }
    }

    private suspend fun fail(message: String) {
        val installed = withContext(Dispatchers.IO) { adapterFiles.isInstalled() }
        _state.update {
            it.copy(
                phase = QqOfficialInstallPhase.Failed,
                installed = installed,
                progress = 0f,
                progressDescription = "QQ 官方机器人适配器安装失败",
                errorMessage = message
            )
        }
        TerminalLogRepository.append("[QQ 官方机器人] 安装失败: $message")
    }
}
