package org.maiwithu.maidroid.oobe

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.diagnostics.AUTO_FIX_LOG_PREFIX
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureCause
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.diagnostics.failureOf
import org.maiwithu.maidroid.maibot.MaiBotAgreement
import org.maiwithu.maidroid.maibot.MaiBotDependencyStep
import org.maiwithu.maidroid.maibot.MaiBotProcessStatus
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import org.maiwithu.maidroid.maibot.MaiBotScripts
import org.maiwithu.maidroid.maibot.MaiBotStartupResult
import org.maiwithu.maidroid.maibot.failure
import org.maiwithu.maidroid.maibot.maiBotRecentRunOutput
import org.maiwithu.maidroid.maibot.maiBotStartupVerdict
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerDns
import org.maiwithu.maidroid.runtime.ContainerRequirement
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.ContainerSetupScripts
import org.maiwithu.maidroid.runtime.NetworkMonitor
import org.maiwithu.maidroid.runtime.NetworkStatus
import org.maiwithu.maidroid.runtime.RuntimeAssetInstaller
import org.maiwithu.maidroid.runtime.RuntimeFileModes
import org.maiwithu.maidroid.runtime.RuntimeInstallStage
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.runtime.StorageSpace
import org.maiwithu.maidroid.runtime.flushFileSystems
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.service.SetupKeepAlive
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.terminal.AnsiText
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.webui.MaiBotWebUi

/**
 * OOBE“配置容器”和“安装 MaiBot”两步的执行者，界面只读它的 state。
 *
 * 由 AppDependencies 持有（整个进程一份），不跟着界面走：转屏、在最近任务里划掉界面，正在装的照样装下去，
 * 重新打开时新界面接着显示同一份进度。每一步开工时让前台服务撑住进程（SetupKeepAlive），切到后台也不会被系统结束。
 * 每一步都能重复执行（做过的会跳过），所以闪退、强制重启、断电后重进也能接着装。
 *
 * 两步都遵守同一条约定：结束时要么完成（可以进入下一步），要么有一张失败卡片、页脚按钮可以重试。
 * 脚本返回成功后还会按文件再核对一次，意外异常也落到失败卡片上，不会停在一个只能等、没有按钮可点的页面。
 * 抗打断的几条做法：开工前看网络和空间（明摆着不行就直接说）-> 每条命令成功后刷盘（断电不留空文件）->
 * 失败时手机已断网就按断网报，等网络恢复后自动重试；其他网络原因隔一会儿自动重试两次。
 */
internal class OobeSetupManager(
    context: Context,
    private val settings: AppSettings,
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner,
    private val maiBotProcess: MaiBotProcessSupervisor,
    private val network: NetworkMonitor
) {
    private companion object {
        const val TAG = "OobeSetupManager"
        const val MAX_COMMAND_LOG_LINES = 500
        const val WEB_UI_POLL_INTERVAL_MS = 1_000L

        // 断网失败后最多等网络恢复这么久，过了就不再自动重试、放开前台服务（用户多半已经不在装了）
        const val OFFLINE_RETRY_WAIT_MS = 30 * 60_000L

        // 要联网的几步：失败时手机已经断网，就按断网报
        val NETWORK_STEPS = setOf(
            FailureStep.AptInstall,
            FailureStep.MaiBotClone,
            FailureStep.UvInstall,
            FailureStep.PythonInstall,
            FailureStep.UvSync
        )
    }

    private val appContext = context.applicationContext
    private val runtimeInstaller = RuntimeAssetInstaller(appContext, paths)
    private val scope = backgroundScope("首次引导", Dispatchers.Main.immediate)
    private var containerJob: Job? = null
    private var installJob: Job? = null

    // 失败后排着的自动重试（等网络恢复，或隔一会儿）；用户点重试、换了一步时取消。
    private var autoRetryJob: Job? = null

    // 这一轮已经因为网络原因自动重试了几次；用户手动重试、某一步成功后清零。
    private var networkAutoRetries = 0

    // WebUI 是否已经探测成功；只在安装完成时置为 true，用来把 WebUI 卡片显示为完成。
    private var webUiReady = false

    // 正在进行的安装卡片；意外异常时把失败记在它上面。
    private var runningInstallTask = OobeTaskKind.MaiBotSource

    private val _state = MutableStateFlow(OobeSetupState.beforeFirstCheck())
    val state: StateFlow<OobeSetupState> = _state.asStateFlow()

    /**
     * “配置容器”一步：进入该步或点“重新配置”时调用。
     * 解包 APK 里的 rootfs、bootstrap 和 proot 依赖（已解包就跳过）-> 确认 proot 可执行 ->
     * 缺系统依赖时：修复 rootfs 权限 -> 建共享存储入口 -> 配 DNS -> 换清华源 -> apt 装 git、curl、证书 ->
     * 按文件核对容器五件套。
     */
    fun prepareContainer() = startContainerStep(manual = true)

    /**
     * “安装 MaiBot”一步：进入该步或点“重试安装”时调用。
     * 配 DNS -> 克隆 MaiBot（已有源码就跳过）-> 装 uv、准备 Python、uv sync（已就绪的部分脚本自己跳过）->
     * 计算协议 hash -> 拉起 MaiBot 并等 WebUI 能访问 -> 记下 OOBE 已完成。
     */
    fun startInstall() = startInstallStep(manual = true)

    // manual：用户进入这一步或点了按钮（取消排着的自动重试、自动重试次数清零）；自动重试时为 false。
    private fun startContainerStep(manual: Boolean) {
        if (containerJob?.isActive == true) return
        cancelAutoRetry(resetCount = manual)
        SetupKeepAlive.hold(appContext)
        containerJob = launchStep(onError = ::failContainer) { runContainerSetup() }
    }

    private fun startInstallStep(manual: Boolean) {
        if (_state.value.isComplete || settings.setupComplete) return
        if (installJob?.isActive == true) return
        cancelAutoRetry(resetCount = manual)
        SetupKeepAlive.hold(appContext)
        installJob = launchStep(onError = { failure -> failInstall(runningInstallTask, failure) }) { runInstall() }
    }

    // 意外异常（读写文件出错、系统拒绝启动服务等）也算这一步失败，交给 onError 标出失败卡片；
    // 不让协程带着“进行中”的卡片悄悄结束，也不让异常冒到主线程让应用闪退。
    // 这一步结束（成功、失败、出异常）后没有排着自动重试，就放开前台服务。
    private fun launchStep(onError: (Failure) -> Unit, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "OOBE step failed", e)
            appendCommandLog("[MaiDroid] ${e.javaClass.simpleName}: ${e.message}")
            onError(Failure(FailureStep.OobeUnexpected, "出错了：${e.message ?: e.javaClass.simpleName}"))
        } finally {
            if (autoRetryJob?.isActive != true) SetupKeepAlive.release()
        }
    }

    private fun cancelAutoRetry(resetCount: Boolean) {
        autoRetryJob?.cancel()
        autoRetryJob = null
        if (resetCount) networkAutoRetries = 0
    }

    /**
     * 失败后按 oobeAutoRetry 排一次自动重试，返回补在页脚失败说明后面的一句，不会自动重试时返回 null。
     * 断网的那种最多等 OFFLINE_RETRY_WAIT_MS，等不到网就不再重试、放开前台服务。
     */
    private fun scheduleAutoRetry(failure: Failure, retry: (manual: Boolean) -> Unit): String? {
        val plan = oobeAutoRetry(failure.cause, networkAutoRetries) ?: return null
        if (plan is OobeAutoRetry.AfterDelay) networkAutoRetries++
        autoRetryJob = scope.launch {
            when (plan) {
                is OobeAutoRetry.AfterReconnect -> {
                    val reconnected = withTimeoutOrNull(OFFLINE_RETRY_WAIT_MS) { network.connected.first { it } }
                    if (reconnected == null) {
                        appendCommandLog("[MaiDroid] 等了 ${OFFLINE_RETRY_WAIT_MS / 60_000} 分钟网络都没恢复，不再自动重试")
                        autoRetryJob = null
                        SetupKeepAlive.release()
                        return@launch
                    }
                    delay(plan.settleMs)
                    appendCommandLog("$AUTO_FIX_LOG_PREFIX 网络恢复了，自动重试")
                }
                is OobeAutoRetry.AfterDelay -> {
                    delay(plan.delayMs)
                    appendCommandLog("$AUTO_FIX_LOG_PREFIX 等了 ${plan.delayMs / 1000} 秒，自动重试")
                }
            }
            autoRetryJob = null
            retry(false)
        }
        return plan.note
    }

    // 要联网的一步失败时手机已经断网了：多半就是断网造成的（下载到一半 Wi-Fi 掉了），原因改报 NET-OFFLINE，
    // 不让“各镜像都超时、解析失败”这些表象把人带偏，也好等网络恢复后自动重试。现读一次网络状态，不用监听里可能滞后的值。
    private fun withOfflineCause(failure: Failure): Failure =
        if (failure.step in NETWORK_STEPS && failure.cause != FailureCause.NoNetwork &&
            !NetworkStatus.read(appContext).connected
        ) {
            failure.copy(cause = FailureCause.NoNetwork)
        } else {
            failure
        }

    private suspend fun runContainerSetup() {
        RuntimeFileModes.ensureExecutableBits(paths)

        if (!readiness.isBaseRuntimeReady()) {
            showContainerProgress(CONTAINER_ASSET_TASKS, "正在准备本地运行环境...")
            // 要从头解包时先看空间（只补 proot 依赖库时只要几百 KB，不用看）；上次解到一半留下的临时目录先删掉，腾出它占的空间
            if (!paths.rootfsMarker.isFile || !paths.bootstrapMarker.isFile) {
                withContext(Dispatchers.IO) { runtimeInstaller.discardStaging() }
                StorageSpace.shortageFailure(
                    FailureStep.ContainerUnpack, paths.appFilesDir, StorageSpace.CONTAINER_UNPACK_BYTES, "解包容器"
                )?.let { failure ->
                    failContainer(failure)
                    return
                }
            }
            val result = runtimeInstaller.installFromAssets { progress ->
                showContainerProgress(progress.stage.affectedTasks(), progress.statusText)
            }
            result.onFailure { error ->
                failContainer(failureOf(FailureStep.ContainerUnpack, error.message ?: "容器资源解包失败"))
                return
            }
            RuntimeFileModes.ensureExecutableBits(paths)
        }

        if (!readiness.isProotRuntimeReady()) {
            failContainer(Failure(FailureStep.ContainerProot, "proot 启动器不可执行，请确认安装的是 arm64 版 APK 后重新安装"))
            return
        }

        // 上次装 Git、curl 时被打断留下 dpkg 半装状态也算没就绪，重跑下面几步：装依赖的脚本会先 dpkg --configure -a 修好
        if (!withContext(Dispatchers.IO) { readiness.isSystemDependencyReady() }) {
            showContainerProgress(SYSTEM_DEPENDENCY_TASKS, "正在修复容器文件权限...")
            withContext(Dispatchers.IO) {
                RuntimeFileModes.makeRootfsWritable(paths)
            }

            val setupSteps = listOf(
                Triple("正在配置共享存储入口...", FailureStep.ContainerStorage, ContainerSetupScripts.setupStorageBridge()),
                Triple(
                    "正在配置容器 DNS...",
                    FailureStep.ContainerDns,
                    ContainerSetupScripts.configureDns(ContainerDns.systemServers(appContext))
                ),
                Triple("正在配置清华 Debian 镜像源...", FailureStep.AptMirror, ContainerSetupScripts.configureAptMirror()),
                Triple(
                    "正在容器内安装 Git、curl 和证书...",
                    FailureStep.AptInstall,
                    ContainerSetupScripts.installSystemDependencies()
                )
            )
            // 最后一步要从软件源下载 Git、curl：手机根本没连网、空间明摆着不够时先说，不用等四个源都超时、装到一半
            val precheck = NetworkStatus.read(appContext).offlineFailure(FailureStep.AptInstall)
                ?: StorageSpace.shortageFailure(
                    FailureStep.AptInstall, paths.appFilesDir, StorageSpace.APT_INSTALL_BYTES, "安装 Git、curl 和证书"
                )
            if (precheck != null) {
                failContainer(precheck)
                return
            }
            for ((statusText, step, script) in setupSteps) {
                showContainerProgress(SYSTEM_DEPENDENCY_TASKS, statusText)
                val result = runCommand(script)
                if (!result.success) {
                    failContainer(failureOf(step, result.summary(), result.output))
                    return
                }
            }
        }

        // 脚本都返回成功也按文件再核对一次，没就绪的部件标成失败、给出重试按钮。
        val notReady = readiness.containerRequirements().firstOrNull { !it.ready }
        if (notReady != null) {
            failContainer(Failure(FailureStep.ContainerCheck, "${notReady.name} 未就绪：${notReady.hint}"))
            return
        }
        showCheckedState()
    }

    private suspend fun runInstall() {
        RuntimeFileModes.ensureExecutableBits(paths)
        // 正常从“配置容器”进来时容器一定就绪（MainViewModel 也会先检查、没就绪就退回上一步），这里只是兜底。
        readiness.containerProblem()?.let { problem ->
            failInstall(
                OobeTaskKind.MaiBotSource,
                Failure(FailureStep.ContainerCheck, "容器还没有配置好（$problem），请返回上一步重新配置")
            )
            return
        }

        // DNS 算在接下来真正要做的那一步上：源码已有就算依赖安装，否则算克隆源码。
        val dnsTaskKind = if (readiness.isMaiBotSourceReady()) {
            OobeTaskKind.PythonDependencies
        } else {
            OobeTaskKind.MaiBotSource
        }
        // 还要克隆源码或装依赖时先看手机连没连网、空间够不够；都装好了只是核对一遍，没网也能走完，不拦
        if (!readiness.isMaiBotSourceReady() || !readiness.isMaiBotDependencyReady()) {
            val networkStep = if (readiness.isMaiBotSourceReady()) FailureStep.UvSync else FailureStep.MaiBotClone
            val precheck = NetworkStatus.read(appContext).offlineFailure(networkStep) ?: installSpaceShortage()
            if (precheck != null) {
                failInstall(dnsTaskKind, precheck)
                return
            }
        }
        val dnsScript = ContainerSetupScripts.configureDns(ContainerDns.systemServers(appContext))
        if (!runInstallCommand(dnsTaskKind, FailureStep.ContainerDns, "正在配置容器 DNS...", dnsScript)) return

        if (!readiness.isMaiBotSourceReady() &&
            !runInstallCommand(OobeTaskKind.MaiBotSource, FailureStep.MaiBotClone, "正在克隆 MaiBot...", MaiBotScripts.clone())
        ) {
            return
        }

        // 依赖三步每次都跑：脚本都能重复执行，已就绪时 uv、Python 立刻跳过，uv sync 只核对一遍（真机上几十毫秒）。
        // 以前按“site-packages 非空”跳过，装包时被打断留下的半套依赖会被当成装好，到启动时才以 WebUI 超时的样子暴露。
        for (step in MaiBotDependencyStep.entries) {
            if (!runInstallCommand(OobeTaskKind.PythonDependencies, step.failureStep, step.progressText, step.script(), step::failure)) {
                return
            }
        }
        // uv sync 返回成功也再按文件核对一次：.venv 里真有装好的包才往下走。
        if (!readiness.isMaiBotDependencyReady()) {
            failInstall(
                OobeTaskKind.PythonDependencies,
                Failure(FailureStep.VenvCheck, "安装 Python 依赖包失败：uv sync 已结束，但虚拟环境里没有装好的依赖包")
            )
            return
        }

        // 协议 hash 在启动 MaiBot 时由 MaiBotAgreement 现算现注入，这一步只提示文件缺失，不拦着安装。
        showInstallRunning(OobeTaskKind.Agreement, "正在计算协议 hash...")
        if (!MaiBotAgreement.filesPresent(paths)) {
            appendCommandLog("[MaiDroid] 没有找到 EULA.md 或 PRIVACY.md，跳过协议确认")
        }

        showInstallRunning(OobeTaskKind.WebUi, "正在启动 MaiBot...")
        // 起不来时从 MaiBot 最近两次运行的输出里认原因（缺依赖、配置文件写错、端口被占……）
        val startup = startMaiBotAndAwaitWebUi()
        startup.failure(maiBotRecentRunOutput(TerminalLog.logs.value.map(AnsiText::plain)))?.let { failure ->
            failInstall(OobeTaskKind.WebUi, failure)
            return
        }

        settings.setupComplete = true
        webUiReady = true
        networkAutoRetries = 0
        _state.value = _state.value.copy(
            installTasks = buildInstallTasks(),
            footerText = "MaiBot WebUI 已启动，可以打开 ${MaiBotWebUi.URL}",
            canInstall = true,
            isComplete = true,
            failure = null
        )
    }

    /**
     * 装源码和依赖前看空间：还缺源码就要克隆加装依赖的量，只缺依赖就要装依赖的量。
     * 不够时再扣掉上次装到一半已经下好的部分（uv 缓存、uv 管理的 Python、.venv），免得重试时被自己下好的缓存挡住；
     * 这些目录可能有几万个文件，只在空间看着不够时才去量。
     */
    private suspend fun installSpaceShortage(): Failure? = withContext(Dispatchers.IO) {
        val sourceReady = readiness.isMaiBotSourceReady()
        val required = (if (sourceReady) 0L else StorageSpace.MAIBOT_CLONE_BYTES) + StorageSpace.MAIBOT_DEPENDENCIES_BYTES
        val step = if (sourceReady) FailureStep.UvSync else FailureStep.MaiBotClone
        val what = if (sourceReady) "安装 Python 依赖" else "下载 MaiBot 源码并安装依赖"
        StorageSpace.shortageFailure(step, paths.appFilesDir, required, what) ?: return@withContext null
        val downloaded = listOf(paths.uvCacheDir, paths.uvPythonDir, paths.venvDir).sumOf(StorageSpace::sizeOf)
        StorageSpace.shortageFailure(step, paths.appFilesDir, required - downloaded, what)
    }

    // 跑一条安装命令：标出进行中的卡片 -> 执行 -> 失败时把带错误码的原因记在这张卡片上并返回 false。
    private suspend fun runInstallCommand(
        kind: OobeTaskKind,
        step: FailureStep,
        statusText: String,
        script: ContainerScript,
        failureOfResult: (ContainerCommandResult) -> Failure = { failureOf(step, it.summary(), it.output) }
    ): Boolean {
        showInstallRunning(kind, statusText)
        val result = runCommand(script)
        if (!result.success) failInstall(kind, failureOfResult(result))
        return result.success
    }

    /**
     * 拉起 MaiBot 并等 WebUI：先请求前台服务（之后常驻），再直接让进程监管器启动。
     * 应用在后台时 Android 12+ 不让新开前台服务，启动服务会抛异常；这时 MaiBot 照样由监管器拉起，
     * 用户回到应用进入主页时 MainViewModel 会再拉一次前台服务。监管器对已在运行的 MaiBot 不会重复启动。
     * 之后每秒按 maiBotStartupVerdict 判断一次，进程反复退出、根本没起来都会立刻给出结论。
     */
    private suspend fun startMaiBotAndAwaitWebUi(): MaiBotStartupResult {
        val baselineExitCount = maiBotProcess.status.value.exitCount
        runCatching { MaiBotRuntimeService.start(appContext) }.onFailure { error ->
            appendCommandLog("[MaiDroid] 前台服务没能启动（${error.javaClass.simpleName}），直接启动 MaiBot")
        }
        maiBotProcess.start()

        val startedAt = SystemClock.elapsedRealtime()
        while (true) {
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val status = maiBotProcess.status.value
            maiBotStartupVerdict(status, baselineExitCount, MaiBotWebUi.isReachable(), elapsedMs)
                ?.let { return it }
            showInstallRunning(OobeTaskKind.WebUi, webUiWaitingText(status, baselineExitCount, elapsedMs))
            delay(WEB_UI_POLL_INTERVAL_MS)
        }
    }

    private fun webUiWaitingText(status: MaiBotProcessStatus, baselineExitCount: Int, elapsedMs: Long): String {
        val exits = status.exitCount - baselineExitCount
        val restartNote = if (exits > 0) "（MaiBot 退出了 $exits 次，正在重启）" else ""
        return "正在等待 MaiBot WebUI 启动... ${elapsedMs / 1000}s$restartNote"
    }

    // 只按文件刷新两步的卡片和页脚，不跑命令。
    private fun showCheckedState() {
        webUiReady = false
        networkAutoRetries = 0
        val containerTasks = buildContainerTasks()
        val ready = containerTasks.all { it.status == OobeTaskStatus.Done }
        _state.value = _state.value.copy(
            containerTasks = containerTasks,
            installTasks = buildInstallTasks(),
            footerText = if (ready) {
                "容器已就绪，可以开始安装 MaiBot。"
            } else {
                "容器还没有准备好，请继续配置 rootfs、proot 和系统依赖。"
            },
            canInstall = ready,
            isComplete = false,
            failure = null
        )
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
    // 协议一步没有要检查的文件（缺文件也不拦着），前面两步完成它就算完成。
    private fun buildInstallTasks(): List<OobeTask> = sequentialTasks(
        OobeTaskCheck(OobeTaskKind.MaiBotSource, "MaiBot 源码", readiness.isMaiBotSourceReady()),
        OobeTaskCheck(OobeTaskKind.PythonDependencies, "Python 依赖", readiness.isMaiBotDependencyReady()),
        OobeTaskCheck(OobeTaskKind.Agreement, "协议确认", done = true),
        OobeTaskCheck(OobeTaskKind.WebUi, "MaiBot WebUI", webUiReady)
    )

    // 把 kind 那张卡片标为进行中，页脚显示当前在做什么。
    private fun showInstallRunning(kind: OobeTaskKind, statusText: String) {
        runningInstallTask = kind
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
            isComplete = false,
            failure = null
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
            isComplete = false,
            failure = null
        )
    }

    // 第一张没完成的容器卡片标为失败（全都就绪时标最后一张），页脚显示失败原因、错误码并给出“重新配置”；
    // 错误码同时写进终端日志，诊断包靠它找出最近的失败。网络原因的失败排一次自动重试，页脚补一句什么时候重试。
    private fun failContainer(reported: Failure) {
        val failure = withOfflineCause(reported)
        appendCommandLog(failure.logLine())
        val retryNote = scheduleAutoRetry(failure) { manual -> startContainerStep(manual) }
        val tasks = buildContainerTasks()
        val failedIndex = tasks.indexOfFirst { it.status != OobeTaskStatus.Done }.takeIf { it >= 0 } ?: tasks.lastIndex
        _state.value = _state.value.copy(
            containerTasks = tasks.mapIndexed { index, task ->
                if (index == failedIndex) task.copy(status = OobeTaskStatus.Failed, statusText = failure.message) else task
            },
            footerText = footerWithRetry(failure.message, retryNote),
            canInstall = false,
            isComplete = false,
            failure = failure
        )
    }

    // 出错的那张安装卡片标为失败；canInstall 置为 true，页脚按钮变成“重试安装”。错误码同样写进终端日志，
    // 网络原因的失败同样排一次自动重试。
    private fun failInstall(kind: OobeTaskKind, reported: Failure) {
        val failure = withOfflineCause(reported)
        appendCommandLog(failure.logLine())
        val retryNote = scheduleAutoRetry(failure) { manual -> startInstallStep(manual) }
        _state.value = _state.value.copy(
            installTasks = buildInstallTasks().map { task ->
                if (task.kind == kind) task.copy(status = OobeTaskStatus.Failed, statusText = failure.message) else task
            },
            footerText = footerWithRetry(failure.message, retryNote),
            canInstall = true,
            isComplete = false,
            failure = failure
        )
    }

    private fun footerWithRetry(message: String, retryNote: String?): String =
        if (retryNote == null) message else "$message（$retryNote）"

    // OOBE 的命令输出同时进入安装页日志和终端日志，命令行本身和退出码也记一笔，方便排查哪一步失败。
    // 成功后刷一次盘：apt、git、uv 刚写下的几百 MB 落到存储上，这时断电也不会留下“文件在、内容空”的半成品。
    private suspend fun runCommand(script: ContainerScript): ContainerCommandResult {
        appendCommandLog("")
        appendCommandLog("$ ${commandRunner.commandLine(script).joinToString(" ")}")
        val result = commandRunner.run(script, onLine = ::appendCommandLog)
        appendCommandLog("exit code: ${result.exitCode}")
        if (result.success) withContext(Dispatchers.IO) { flushFileSystems() }
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
