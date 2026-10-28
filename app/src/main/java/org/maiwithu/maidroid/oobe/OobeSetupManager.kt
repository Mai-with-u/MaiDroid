package org.maiwithu.maidroid.oobe

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerRequirement
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeAssetInstaller
import org.maiwithu.maidroid.runtime.RuntimeInstallProgress
import org.maiwithu.maidroid.maibot.MaiBotScripts
import org.maiwithu.maidroid.process.TerminalLogRepository
import org.maiwithu.maidroid.repository.SettingsRepository
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ContainerSetupScripts
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.service.ChatbotService
import org.maiwithu.maidroid.webui.MaiBotWebUiProbe

enum class OobeSetupPhase {
    Container,
    Install,
    Complete
}

enum class OobeTaskKind {
    Container,
    Source,
    Dependency,
    Agreement,
    WebUi
}

enum class OobeTaskStatus {
    Waiting,
    Running,
    Done,
    Blocked,
    Failed
}

data class OobeTaskState(
    val kind: OobeTaskKind,
    val title: String,
    val description: String,
    val progress: Float,
    val progressText: String,
    val statusText: String,
    val status: OobeTaskStatus
)

data class OobeSetupState(
    val phase: OobeSetupPhase,
    val containerTasks: List<OobeTaskState>,
    val installTasks: List<OobeTaskState>,
    val footerText: String,
    val canInstall: Boolean,
    val isComplete: Boolean,
    val webUiReady: Boolean = false,
    val webUiUrl: String = RuntimePaths.WEB_UI_URL,
    val commandLogs: List<String> = emptyList()
) {
    companion object {
        fun preview(): OobeSetupState {
            val containerTasks = listOf(
                OobeTaskState(
                    kind = OobeTaskKind.Container,
                    title = "Debian rootfs",
                    description = "检查 rootfs 目录",
                    progress = 0f,
                    progressText = "等待",
                    statusText = "等待容器资源",
                    status = OobeTaskStatus.Waiting
                ),
                OobeTaskState(
                    kind = OobeTaskKind.Container,
                    title = "Termux bootstrap",
                    description = "检查 bootstrap 资源",
                    progress = 0f,
                    progressText = "等待",
                    statusText = "等待 bootstrap 解包",
                    status = OobeTaskStatus.Waiting
                ),
                OobeTaskState(
                    kind = OobeTaskKind.Container,
                    title = "proot",
                    description = "检查容器启动器和 loader",
                    progress = 0f,
                    progressText = "等待",
                    statusText = "等待可执行文件",
                    status = OobeTaskStatus.Waiting
                )
            )
            return OobeSetupState(
                phase = OobeSetupPhase.Container,
                containerTasks = containerTasks,
                installTasks = defaultInstallTasks(),
                footerText = "正在检查容器配置...",
                canInstall = false,
                isComplete = false
            )
        }

        fun defaultInstallTasks(): List<OobeTaskState> = listOf(
            OobeTaskState(
                kind = OobeTaskKind.Source,
                title = "MaiBot 源码",
                description = GitHubMirrors.DESCRIPTION,
                progress = 0f,
                progressText = "等待",
                statusText = "等待容器就绪",
                status = OobeTaskStatus.Waiting
            ),
            OobeTaskState(
                kind = OobeTaskKind.Dependency,
                title = "Python 依赖",
                description = "uv sync",
                progress = 0f,
                progressText = "等待",
                statusText = "等待源码",
                status = OobeTaskStatus.Waiting
            ),
            OobeTaskState(
                kind = OobeTaskKind.Agreement,
                title = "协议确认",
                description = "EULA_AGREE / PRIVACY_AGREE",
                progress = 0f,
                progressText = "等待",
                statusText = "等待 EULA 文件",
                status = OobeTaskStatus.Waiting
            ),
            OobeTaskState(
                kind = OobeTaskKind.WebUi,
                title = "MaiBot WebUI",
                description = RuntimePaths.WEB_UI_URL,
                progress = 0f,
                progressText = "等待",
                statusText = "等待 MaiBot 启动",
                status = OobeTaskStatus.Waiting
            )
        )
    }
}

class OobeSetupManager(
    context: Context,
    private val settingsRepository: SettingsRepository = SettingsRepository()
) {
    companion object {
        private const val MAX_COMMAND_LOG_LINES = 500
        private const val WEB_UI_START_TIMEOUT_MS = 180_000L
        private const val WEB_UI_POLL_INTERVAL_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val config = RuntimePaths.from(appContext)
    private val runtimeInstaller = RuntimeAssetInstaller(appContext, config)
    private val commandRunner = ContainerCommandRunner(config)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var containerJob: Job? = null
    private var installJob: Job? = null

    private val _state = MutableStateFlow(OobeSetupState.preview())
    val state: StateFlow<OobeSetupState> = _state.asStateFlow()

    fun close() {
        scope.cancel()
    }

    fun refreshContainer() {
        scope.launch {
            config.ensureExecutableBits()
            val containerTasks = buildContainerTasks()
            val installTasks = buildInstallTasks()
            val ready = containerTasks.all { it.status == OobeTaskStatus.Done }
            _state.value = OobeSetupState(
                phase = OobeSetupPhase.Container,
                containerTasks = containerTasks,
                installTasks = installTasks,
                footerText = if (ready) {
                    "容器已就绪，可以开始安装 MaiBot。"
                } else {
                    "容器还没有准备好，请继续配置 rootfs、proot 和系统依赖。"
                },
                canInstall = ready,
                isComplete = false,
                commandLogs = _state.value.commandLogs
            )
        }
    }

    fun prepareContainer() {
        if (containerJob?.isActive == true) return
        containerJob = scope.launch {
            config.ensureExecutableBits()
            if (config.isContainerReady()) {
                refreshContainer()
                return@launch
            }

            if (!config.isBaseRuntimeReady()) {
                updateContainerProgress(
                    RuntimeInstallProgress(
                        title = "容器资源",
                        statusText = "正在准备本地运行环境...",
                        progress = 0.05f
                    )
                )

                val result = runtimeInstaller.installFromAssets { progress ->
                    updateContainerProgress(progress)
                }

                result.onFailure { error ->
                    failContainer(error.message ?: "容器资源解包失败")
                    return@launch
                }

                config.ensureExecutableBits()
            }

            if (!config.prootExecutable.isFile || !config.prootLoader.isFile ||
                !config.prootExecutable.canExecute() || !config.prootLoader.canExecute()
            ) {
                failContainer("proot 启动器不可执行，请确认 APK 已包含 arm64 native runtime 后重新安装")
                return@launch
            }

            if (!config.isSystemDependencyReady()) {
                updateContainerProgress(
                    RuntimeInstallProgress(
                        title = "系统依赖",
                        statusText = "正在修复容器文件权限...",
                        progress = 0.68f
                    )
                )
                withContext(Dispatchers.IO) {
                    config.ensureWritableRuntimePaths()
                }

                updateContainerProgress(
                    RuntimeInstallProgress(
                        title = "系统依赖",
                        statusText = "正在配置共享存储入口...",
                        progress = 0.70f
                    )
                )
                val storageBridgeResult = runCommand(ContainerSetupScripts.setupStorageBridge())
                if (!storageBridgeResult.success) {
                    failContainer(storageBridgeResult.summary())
                    return@launch
                }

                updateContainerProgress(
                    RuntimeInstallProgress(
                        title = "系统依赖",
                        statusText = "正在配置容器 DNS...",
                        progress = 0.72f
                    )
                )
                val dnsResult = runCommand(ContainerSetupScripts.configureDns())
                if (!dnsResult.success) {
                    failContainer(dnsResult.summary())
                    return@launch
                }

                updateContainerProgress(
                    RuntimeInstallProgress(
                        title = "系统依赖",
                        statusText = "正在配置清华 Debian 镜像源...",
                        progress = 0.76f
                    )
                )
                val mirrorResult = runCommand(ContainerSetupScripts.configureAptMirror())
                if (!mirrorResult.success) {
                    failContainer(mirrorResult.summary())
                    return@launch
                }

                updateContainerProgress(
                    RuntimeInstallProgress(
                        title = "系统依赖",
                        statusText = "正在容器内安装 Git、curl 和证书...",
                        progress = 0.82f
                    )
                )
                val dependencyResult = runCommand(ContainerSetupScripts.installSystemDependencies())
                if (!dependencyResult.success) {
                    failContainer(dependencyResult.summary())
                    return@launch
                }
            }

            refreshContainer()
        }
    }

    fun startInstall() {
        if (_state.value.isComplete || settingsRepository.isSetupComplete()) return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            config.ensureExecutableBits()
            if (!config.isContainerReady()) {
                refreshContainer()
                return@launch
            }

            val dnsTaskKind = if (config.isSourceReady()) {
                OobeTaskKind.Dependency
            } else {
                OobeTaskKind.Source
            }
            updateInstallState(
                buildInstallTasks().markRunning(dnsTaskKind, "正在配置容器 DNS...")
            )
            val dnsResult = runCommand(ContainerSetupScripts.configureDns())
            if (!dnsResult.success) {
                failInstall(dnsTaskKind, dnsResult.summary())
                return@launch
            }

            if (!config.isSourceReady()) {
                updateInstallState(
                    buildInstallTasks().markRunning(OobeTaskKind.Source, "正在克隆 MaiBot...")
                )
                val cloneResult = runCommand(MaiBotScripts.clone())
                if (!cloneResult.success) {
                    failInstall(OobeTaskKind.Source, cloneResult.summary())
                    return@launch
                }
            }

            updateInstallState(
                buildInstallTasks().markRunning(OobeTaskKind.Dependency, "正在安装 uv 和依赖...")
            )

            if (!config.userUvExecutable.isFile) {
                val uvResult = runCommand(MaiBotScripts.installUv())
                if (!uvResult.success) {
                    failInstall(OobeTaskKind.Dependency, uvResult.summary())
                    return@launch
                }
            }

            if (!config.isDependencyReady()) {
                val syncResult = runCommand(MaiBotScripts.syncDependencies())
                if (!syncResult.success) {
                    failInstall(OobeTaskKind.Dependency, syncResult.summary())
                    return@launch
                }
            }

            updateInstallState(
                buildInstallTasks().markRunning(OobeTaskKind.Agreement, "正在读取协议 hash...")
            )

            val agreementReady = config.agreementEnvironment().containsKey("EULA_AGREE")
            if (!agreementReady) {
                failInstall(OobeTaskKind.Agreement, "未找到 EULA.md 或 PRIVACY.md")
                return@launch
            }

            updateInstallState(
                buildInstallTasks().markRunning(OobeTaskKind.WebUi, "正在启动 MaiBot WebUI...")
            )

            ChatbotService.start(appContext)

            val webUiReady = waitForWebUi()
            if (!webUiReady) {
                failInstall(OobeTaskKind.WebUi, "MaiBot WebUI 启动超时，请查看调试日志")
                return@launch
            }

            settingsRepository.setSetupComplete(true)

            _state.value = _state.value.copy(
                phase = OobeSetupPhase.Complete,
                installTasks = buildInstallTasks(webUiReady = true),
                footerText = "MaiBot WebUI 已启动，可以打开 ${RuntimePaths.WEB_UI_URL}",
                canInstall = true,
                isComplete = true,
                webUiReady = true
            )
        }
    }

    private fun buildContainerTasks(): List<OobeTaskState> =
        config.containerRequirements().map { requirement ->
            requirement.toTaskState()
        }

    private fun buildInstallTasks(
        webUiReady: Boolean = _state.value.webUiReady
    ): List<OobeTaskState> {
        val sourceReady = config.isSourceReady()
        val dependencyReady = config.isDependencyReady()
        val agreementReady = config.agreementEnvironment().containsKey("EULA_AGREE")
        return listOf(
            OobeTaskState(
                kind = OobeTaskKind.Source,
                title = "MaiBot 源码",
                description = "git clone，GitHub 镜像每源重试 ${GitHubMirrors.RETRY_PER_MIRROR} 次",
                progress = if (sourceReady) 1f else 0f,
                progressText = if (sourceReady) "完成" else "等待",
                statusText = if (sourceReady) config.maiBotDir.absolutePath else "等待 git clone",
                status = if (sourceReady) OobeTaskStatus.Done else OobeTaskStatus.Waiting
            ),
            OobeTaskState(
                kind = OobeTaskKind.Dependency,
                title = "Python 依赖",
                description = "uv sync",
                progress = if (dependencyReady) 1f else 0f,
                progressText = if (dependencyReady) "完成" else "等待",
                statusText = if (dependencyReady) ".venv 已存在" else "等待 uv sync",
                status = if (dependencyReady) OobeTaskStatus.Done else OobeTaskStatus.Waiting
            ),
            OobeTaskState(
                kind = OobeTaskKind.Agreement,
                title = "协议确认",
                description = "自动注入 EULA_AGREE / PRIVACY_AGREE",
                progress = if (agreementReady) 1f else 0f,
                progressText = if (agreementReady) "完成" else "等待",
                statusText = if (agreementReady) "hash 已就绪" else "等待协议文件",
                status = if (agreementReady) OobeTaskStatus.Done else OobeTaskStatus.Waiting
            ),
            OobeTaskState(
                kind = OobeTaskKind.WebUi,
                title = "MaiBot WebUI",
                description = RuntimePaths.WEB_UI_URL,
                progress = if (webUiReady) 1f else 0f,
                progressText = if (webUiReady) "完成" else "等待",
                statusText = when {
                    webUiReady -> "WebUI 已启动"
                    agreementReady -> "等待 MaiBot 启动"
                    else -> "等待协议确认"
                },
                status = if (webUiReady) OobeTaskStatus.Done else OobeTaskStatus.Waiting
            )
        )
    }

    private fun updateInstallState(installTasks: List<OobeTaskState>, footer: String? = null) {
        _state.value = _state.value.copy(
            phase = OobeSetupPhase.Install,
            containerTasks = buildContainerTasks(),
            installTasks = installTasks,
            footerText = footer ?: installTasks.firstOrNull { it.status == OobeTaskStatus.Running }?.statusText
                ?: "正在安装 MaiBot...",
            canInstall = false,
            isComplete = false
        )
    }

    private fun updateContainerProgress(progress: RuntimeInstallProgress) {
        val runningTaskNames = when (progress.title) {
            "Debian rootfs" -> setOf("Debian rootfs")
            "Termux bootstrap" -> setOf("Termux bootstrap", "proot")
            "proot" -> setOf("proot")
            "系统依赖" -> setOf("Git", "curl / CA")
            else -> setOf("Debian rootfs", "Termux bootstrap", "proot")
        }
        val tasks = buildContainerTasks().map { task ->
            when {
                task.status == OobeTaskStatus.Done -> task
                task.title in runningTaskNames -> task.copy(
                    progress = progress.progress,
                    progressText = "进行中",
                    statusText = progress.statusText,
                    status = OobeTaskStatus.Running
                )
                task.status == OobeTaskStatus.Blocked -> task.copy(
                    progressText = "等待",
                    statusText = "等待前置步骤",
                    status = OobeTaskStatus.Waiting
                )
                else -> task
            }
        }
        _state.value = _state.value.copy(
            phase = OobeSetupPhase.Container,
            containerTasks = tasks,
            footerText = progress.statusText,
            canInstall = false,
            isComplete = false
        )
    }

    private fun failContainer(message: String) {
        val tasks = buildContainerTasks()
        val failedIndex = tasks.indexOfFirst { it.status != OobeTaskStatus.Done }
        val failedTasks = tasks.mapIndexed { index, task ->
            if (index == failedIndex) {
                task.copy(
                    progressText = "失败",
                    statusText = message,
                    status = OobeTaskStatus.Failed
                )
            } else {
                task
            }
        }
        _state.value = _state.value.copy(
            phase = OobeSetupPhase.Container,
            containerTasks = failedTasks,
            footerText = message,
            canInstall = false,
            isComplete = false
        )
    }

    private fun failInstall(kind: OobeTaskKind, message: String) {
        val failedTasks = buildInstallTasks().map { task ->
            if (task.kind == kind) {
                task.copy(
                    progress = 0f,
                    progressText = "失败",
                    statusText = message,
                    status = OobeTaskStatus.Failed
                )
            } else {
                task
            }
        }
        _state.value = _state.value.copy(
            phase = OobeSetupPhase.Install,
            installTasks = failedTasks,
            footerText = message,
            canInstall = true,
            isComplete = false
        )
    }

    private fun ContainerRequirement.toTaskState(): OobeTaskState =
        OobeTaskState(
            kind = OobeTaskKind.Container,
            title = name,
            description = path,
            progress = if (ready) 1f else 0f,
            progressText = if (ready) "完成" else "缺失",
            statusText = if (ready) "已就绪" else hint,
            status = if (ready) OobeTaskStatus.Done else OobeTaskStatus.Blocked
        )

    private fun List<OobeTaskState>.markRunning(
        kind: OobeTaskKind,
        statusText: String
    ): List<OobeTaskState> = map { task ->
        if (task.kind == kind) {
            task.copy(
                progress = 0.5f,
                progressText = "进行中",
                statusText = statusText,
                status = OobeTaskStatus.Running
            )
        } else {
            task
        }
    }

    private suspend fun waitForWebUi(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + WEB_UI_START_TIMEOUT_MS
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt += 1
            if (MaiBotWebUiProbe.isReachable()) return true

            updateInstallState(
                buildInstallTasks().markRunning(
                    OobeTaskKind.WebUi,
                    "正在等待 MaiBot WebUI 启动... ${attempt}s"
                )
            )
            delay(WEB_UI_POLL_INTERVAL_MS)
        }
        return false
    }

    // OOBE 的命令输出同时进入安装页日志和终端日志，命令行本身和退出码也记一笔，方便排查哪一步失败。
    private suspend fun runCommand(script: ContainerScript): ContainerCommandResult {
        appendCommandLog("")
        appendCommandLog("$ ${commandRunner.commandLine(script).joinToString(" ")}")
        val result = commandRunner.run(script, onLine = ::appendCommandLog)
        appendCommandLog("exit code: ${result.exitCode}")
        return result
    }

    private fun appendCommandLog(line: String) {
        val nextLogs = (_state.value.commandLogs + line).takeLast(MAX_COMMAND_LOG_LINES)
        _state.value = _state.value.copy(commandLogs = nextLogs)
        TerminalLogRepository.append(line)
    }
}
