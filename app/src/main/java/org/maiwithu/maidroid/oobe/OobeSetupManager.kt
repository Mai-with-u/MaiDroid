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
import org.maiwithu.maidroid.log.TerminalLog
import org.maiwithu.maidroid.maibot.MaiBotAgreement
import org.maiwithu.maidroid.maibot.MaiBotScripts
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerRequirement
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ContainerSetupScripts
import org.maiwithu.maidroid.runtime.RuntimeAssetInstaller
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimeInstallStage
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.webui.MaiBotWebUi

/**
 * OOBE“配置容器”和“安装 MaiBot”两步的执行者，界面只读它的 state。
 *
 * 由 MainViewModel 持有，屏幕旋转等配置变化不会打断正在进行的安装；
 * 每一步都先按文件判断是否已经做过，做过就跳过，所以中途退出后重进可以接着装。
 */
internal class OobeSetupManager(
    context: Context,
    private val settings: AppSettings,
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner
) {
    private companion object {
        const val MAX_COMMAND_LOG_LINES = 500
        const val WEB_UI_START_TIMEOUT_MS = 180_000L
        const val WEB_UI_POLL_INTERVAL_MS = 1_000L
    }

    private val appContext = context.applicationContext
    private val runtimeInstaller = RuntimeAssetInstaller(appContext, paths)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var containerJob: Job? = null
    private var installJob: Job? = null

    // WebUI 是否已经探测成功；只在安装完成时置为 true，用来把 WebUI 卡片显示为完成。
    private var webUiReady = false

    private val _state = MutableStateFlow(OobeSetupState.beforeFirstCheck())
    val state: StateFlow<OobeSetupState> = _state.asStateFlow()

    /** ViewModel 销毁时调用，取消还在跑的检查和安装。 */
    fun close() {
        scope.cancel()
    }

    /** 按文件重新检查容器五件套，刷新两步的卡片和页脚。 */
    fun refreshContainer() {
        scope.launch {
            RuntimeFileModes.ensureExecutableBits(paths)
            webUiReady = false
            val containerTasks = buildContainerTasks()
            val ready = containerTasks.all { it.status == OobeTaskStatus.Done }
            _state.value = OobeSetupState(
                containerTasks = containerTasks,
                installTasks = buildInstallTasks(),
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

    /**
     * “配置容器”一步：进入该步或点“重新配置”时调用。
     * 解包 APK 里的 rootfs、bootstrap 和 proot 依赖（已解包就跳过）-> 确认 proot 可执行 ->
     * 缺系统依赖时：修复 rootfs 权限 -> 建共享存储入口 -> 配 DNS -> 换清华源 -> apt 装 git、curl、证书。
     */
    fun prepareContainer() {
        if (containerJob?.isActive == true) return
        containerJob = scope.launch {
            RuntimeFileModes.ensureExecutableBits(paths)
            if (readiness.isContainerReady()) {
                refreshContainer()
                return@launch
            }

            if (!readiness.isBaseRuntimeReady()) {
                showContainerProgress(CONTAINER_ASSET_TASKS, "正在准备本地运行环境...")

                val result = runtimeInstaller.installFromAssets { progress ->
                    showContainerProgress(progress.stage.affectedTasks(), progress.statusText)
                }

                result.onFailure { error ->
                    failContainer(error.message ?: "容器资源解包失败")
                    return@launch
                }

                RuntimeFileModes.ensureExecutableBits(paths)
            }

            if (!paths.prootExecutable.isFile || !paths.prootLoader.isFile ||
                !paths.prootExecutable.canExecute() || !paths.prootLoader.canExecute()
            ) {
                failContainer("proot 启动器不可执行，请确认 APK 已包含 arm64 native runtime 后重新安装")
                return@launch
            }

            if (!readiness.isSystemDependencyReady()) {
                showContainerProgress(SYSTEM_DEPENDENCY_TASKS, "正在修复容器文件权限...")
                withContext(Dispatchers.IO) {
                    RuntimeFileModes.makeRootfsWritable(paths)
                }

                val setupSteps = listOf(
                    "正在配置共享存储入口..." to ContainerSetupScripts.setupStorageBridge(),
                    "正在配置容器 DNS..." to ContainerSetupScripts.configureDns(),
                    "正在配置清华 Debian 镜像源..." to ContainerSetupScripts.configureAptMirror(),
                    "正在容器内安装 Git、curl 和证书..." to ContainerSetupScripts.installSystemDependencies()
                )
                for ((statusText, script) in setupSteps) {
                    showContainerProgress(SYSTEM_DEPENDENCY_TASKS, statusText)
                    val result = runCommand(script)
                    if (!result.success) {
                        failContainer(result.summary())
                        return@launch
                    }
                }
            }

            refreshContainer()
        }
    }

    /**
     * “安装 MaiBot”一步：进入该步或点“重试安装”时调用。
     * 配 DNS -> 克隆 MaiBot（已有源码就跳过）-> 装 uv 并 uv sync（已装好就跳过）-> 确认协议文件 ->
     * 拉起前台服务启动 MaiBot -> 最多等 3 分钟直到 WebUI 能访问 -> 记下 OOBE 已完成。
     */
    fun startInstall() {
        if (_state.value.isComplete || settings.setupComplete) return
        if (installJob?.isActive == true) return
        installJob = scope.launch {
            RuntimeFileModes.ensureExecutableBits(paths)
            if (!readiness.isContainerReady()) {
                refreshContainer()
                return@launch
            }

            // DNS 算在接下来真正要做的那一步上：源码已有就算依赖安装，否则算克隆源码。
            val dnsTaskKind = if (readiness.isMaiBotSourceReady()) {
                OobeTaskKind.PythonDependencies
            } else {
                OobeTaskKind.MaiBotSource
            }
            showInstallRunning(dnsTaskKind, "正在配置容器 DNS...")
            val dnsResult = runCommand(ContainerSetupScripts.configureDns())
            if (!dnsResult.success) {
                failInstall(dnsTaskKind, dnsResult.summary())
                return@launch
            }

            if (!readiness.isMaiBotSourceReady()) {
                showInstallRunning(OobeTaskKind.MaiBotSource, "正在克隆 MaiBot...")
                val cloneResult = runCommand(MaiBotScripts.clone())
                if (!cloneResult.success) {
                    failInstall(OobeTaskKind.MaiBotSource, cloneResult.summary())
                    return@launch
                }
            }

            showInstallRunning(OobeTaskKind.PythonDependencies, "正在安装 uv 和依赖...")

            if (!paths.userUvExecutable.isFile) {
                val uvResult = runCommand(MaiBotScripts.installUv())
                if (!uvResult.success) {
                    failInstall(OobeTaskKind.PythonDependencies, uvResult.summary())
                    return@launch
                }
            }

            if (!readiness.isMaiBotDependencyReady()) {
                val syncResult = runCommand(MaiBotScripts.syncDependencies())
                if (!syncResult.success) {
                    failInstall(OobeTaskKind.PythonDependencies, syncResult.summary())
                    return@launch
                }
            }

            showInstallRunning(OobeTaskKind.Agreement, "正在读取协议 hash...")

            if (!MaiBotAgreement.isReady(paths)) {
                failInstall(OobeTaskKind.Agreement, "未找到 EULA.md 或 PRIVACY.md")
                return@launch
            }

            showInstallRunning(OobeTaskKind.WebUi, "正在启动 MaiBot WebUI...")

            MaiBotRuntimeService.start(appContext)

            if (!waitForWebUi()) {
                failInstall(OobeTaskKind.WebUi, "MaiBot WebUI 启动超时，请查看调试日志")
                return@launch
            }

            settings.setupComplete = true
            webUiReady = true

            _state.value = _state.value.copy(
                installTasks = buildInstallTasks(),
                footerText = "MaiBot WebUI 已启动，可以打开 ${MaiBotWebUi.URL}",
                canInstall = true,
                isComplete = true
            )
        }
    }

    private fun buildContainerTasks(): List<OobeTask> =
        readiness.containerRequirements().map { it.toTask() }

    private fun ContainerRequirement.toTask(): OobeTask =
        OobeTask(
            kind = component.toOobeTaskKind(),
            title = name,
            status = if (ready) OobeTaskStatus.Done else OobeTaskStatus.Blocked,
            statusText = if (ready) "" else hint
        )

    // 安装四步按文件判断是否已完成，没完成的都显示为等待；正在做的那一步由 showInstallRunning 单独标出。
    private fun buildInstallTasks(): List<OobeTask> {
        fun task(kind: OobeTaskKind, title: String, done: Boolean) =
            OobeTask(kind, title, if (done) OobeTaskStatus.Done else OobeTaskStatus.Waiting)
        return listOf(
            task(OobeTaskKind.MaiBotSource, "MaiBot 源码", readiness.isMaiBotSourceReady()),
            task(OobeTaskKind.PythonDependencies, "Python 依赖", readiness.isMaiBotDependencyReady()),
            task(OobeTaskKind.Agreement, "协议确认", MaiBotAgreement.isReady(paths)),
            task(OobeTaskKind.WebUi, "MaiBot WebUI", webUiReady)
        )
    }

    // 把 kind 那张卡片标为进行中，页脚显示当前在做什么。
    private fun showInstallRunning(kind: OobeTaskKind, statusText: String) {
        val tasks = buildInstallTasks().map { task ->
            if (task.kind == kind) {
                task.copy(status = OobeTaskStatus.Running, statusText = statusText)
            } else {
                task
            }
        }
        _state.value = _state.value.copy(
            containerTasks = buildContainerTasks(),
            installTasks = tasks,
            footerText = statusText,
            canInstall = false,
            isComplete = false
        )
    }

    // 解包或装系统依赖时，把还没完成的相关卡片标为进行中；其余没就绪的卡片显示为等待前置步骤。
    private fun showContainerProgress(runningTasks: Set<OobeTaskKind>, statusText: String) {
        val tasks = buildContainerTasks().map { task ->
            when {
                task.status == OobeTaskStatus.Done -> task
                task.kind in runningTasks -> task.copy(status = OobeTaskStatus.Running, statusText = statusText)
                task.status == OobeTaskStatus.Blocked -> task.copy(status = OobeTaskStatus.Waiting, statusText = "等待前置步骤")
                else -> task
            }
        }
        _state.value = _state.value.copy(
            containerTasks = tasks,
            footerText = statusText,
            canInstall = false,
            isComplete = false
        )
    }

    // 第一张没完成的容器卡片标为失败，页脚显示失败原因。
    private fun failContainer(message: String) {
        val tasks = buildContainerTasks()
        val failedIndex = tasks.indexOfFirst { it.status != OobeTaskStatus.Done }
        _state.value = _state.value.copy(
            containerTasks = tasks.mapIndexed { index, task ->
                if (index == failedIndex) task.copy(status = OobeTaskStatus.Failed, statusText = message) else task
            },
            footerText = message,
            canInstall = false,
            isComplete = false
        )
    }

    // 出错的那张安装卡片标为失败；canInstall 置为 true，页脚按钮变成“重试安装”。
    private fun failInstall(kind: OobeTaskKind, message: String) {
        _state.value = _state.value.copy(
            installTasks = buildInstallTasks().map { task ->
                if (task.kind == kind) task.copy(status = OobeTaskStatus.Failed, statusText = message) else task
            },
            footerText = message,
            canInstall = true,
            isComplete = false
        )
    }

    // 每秒探测一次 WebUI，同时把已等待的秒数显示在页脚；超时返回 false。
    private suspend fun waitForWebUi(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + WEB_UI_START_TIMEOUT_MS
        var attempt = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempt += 1
            if (MaiBotWebUi.isReachable()) return true

            showInstallRunning(OobeTaskKind.WebUi, "正在等待 MaiBot WebUI 启动... ${attempt}s")
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
        TerminalLog.append(line)
    }
}

// 解包 APK 资源时受影响的三张卡片，以及安装系统依赖时受影响的两张。
private val CONTAINER_ASSET_TASKS = setOf(OobeTaskKind.DebianRootfs, OobeTaskKind.TermuxBootstrap, OobeTaskKind.Proot)
private val SYSTEM_DEPENDENCY_TASKS = setOf(OobeTaskKind.Git, OobeTaskKind.CurlCa)

private fun RuntimeInstallStage.affectedTasks(): Set<OobeTaskKind> = when (this) {
    RuntimeInstallStage.DebianRootfs -> setOf(OobeTaskKind.DebianRootfs)
    RuntimeInstallStage.TermuxBootstrap -> setOf(OobeTaskKind.TermuxBootstrap, OobeTaskKind.Proot)
    RuntimeInstallStage.WholeRuntime -> CONTAINER_ASSET_TASKS
}
