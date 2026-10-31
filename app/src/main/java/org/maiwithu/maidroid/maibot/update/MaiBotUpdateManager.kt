package org.maiwithu.maidroid.maibot.update

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
import org.maiwithu.maidroid.maibot.MaiBotDependencyStep
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import org.maiwithu.maidroid.runtime.ContainerCommandResult
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.ContainerScript
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.service.MaiBotRuntimeService
import org.maiwithu.maidroid.terminal.TerminalLog

/** 在线更新弹窗所处的阶段：空闲 -> 检查中 -> 已是最新 / 有新版本 -> 更新中 -> 已更新，任一步出错进入失败。 */
internal enum class MaiBotUpdatePhase {
    Idle,
    Checking,
    UpToDate,
    Available,
    Updating,
    Updated,
    Failed
}

/** 失败发生在哪一步，决定弹窗里“重试”该重新检查还是重新更新。 */
internal enum class MaiBotUpdateAction {
    Check,
    Apply
}

/** 在线更新的界面状态：当前与上游版本、用户改过的源码文件数、进行中的步骤说明和失败原因。 */
internal data class MaiBotUpdateState(
    val phase: MaiBotUpdatePhase = MaiBotUpdatePhase.Idle,
    val branch: String? = null,
    val installed: MaiBotRevision? = null,
    val latest: MaiBotRevision? = null,
    val localChangeCount: Int = 0,
    val progressText: String? = null,
    val errorMessage: String? = null,
    val failedAction: MaiBotUpdateAction? = null
) {
    val isBusy: Boolean
        get() = phase == MaiBotUpdatePhase.Checking || phase == MaiBotUpdatePhase.Updating
}

/**
 * MaiBot 在线更新：检查上游 -> 停止麦麦 -> 切换源码 -> 同步依赖 -> 重启麦麦。
 *
 * 进程级单例，切到后台或关掉设置页后更新照常进行，回到设置页能看到最新进度。
 * 每次打开应用进入主页时主界面会调 [checkOnLaunch] 自动查一次，查到新版本就弹窗提醒。
 * 所有命令输出都会写进终端日志，失败时可以在终端里看完整过程。
 */
internal class MaiBotUpdateManager(
    private val appContext: Context,
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner,
    private val maiBotProcess: MaiBotProcessSupervisor
) {
    companion object {
        private const val LOG_TAG = "[MaiBot 更新]"
    }

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var job: Job? = null
    private var revisionJob: Job? = null

    private val _state = MutableStateFlow(MaiBotUpdateState())
    val state: StateFlow<MaiBotUpdateState> = _state.asStateFlow()

    private val _launchPrompt = MutableStateFlow(false)

    /** 启动时的自动检查查到了上游新版本、用户还没关掉提醒：主界面据此在主页上弹出更新弹窗。 */
    val launchPrompt: StateFlow<Boolean> = _launchPrompt.asStateFlow()

    // 正在进行的这次检查是不是启动时的自动检查：是的话查到新版本要弹窗提醒，查失败了不打扰用户。
    // 用户在设置页打开 MaiBot 更新弹窗后改回 false，结果直接显示在那个弹窗里。
    private var checkingOnLaunch = false

    /**
     * 设置页打开时读一次当前安装的版本，只读本地仓库，不联网也不打断麦麦。
     * 与检查、更新互不阻塞；若检查或更新已经先写入了版本信息，以它们的结果为准。
     */
    fun loadInstalledRevision() {
        if (revisionJob?.isActive == true || _state.value.installed != null) return
        revisionJob = scope.launch {
            if (validationError() != null) return@launch
            val result = runCommand(MaiBotUpdateScripts.installedRevision(), logOutput = false)
            val report = parseMaiBotUpdateReport(result.output)
            if (!result.success || report.installed == null) return@launch
            _state.update {
                if (it.installed != null) {
                    it
                } else {
                    it.copy(
                        branch = report.branch,
                        installed = report.installed,
                        localChangeCount = report.localChangeCount ?: it.localChangeCount
                    )
                }
            }
        }
    }

    /** 检查更新：经 GitHub 镜像拉取上游最新提交并与当前版本比较，不改动工作区，麦麦照常运行。 */
    fun checkForUpdate() = startCheck(onLaunch = false)

    /**
     * 打开应用进入主页时自动检查一次：和手动检查走同一套流程（只拉取上游提交，不动工作区，麦麦照常启动运行），
     * 查到新版本时置位 [launchPrompt]，主页弹窗提醒用户更新；已是最新、拉取失败都不提示。
     * 正在更新时跳过，不打断。
     */
    fun checkOnLaunch() = startCheck(onLaunch = true)

    /** 不再弹启动提醒：用户关掉了提醒弹窗，或者自己在设置页打开了 MaiBot 更新弹窗（结果改在那里显示）。 */
    fun dismissLaunchPrompt() {
        checkingOnLaunch = false
        _launchPrompt.value = false
    }

    private fun startCheck(onLaunch: Boolean) {
        if (job?.isActive == true) return
        checkingOnLaunch = onLaunch
        job = scope.launch {
            showProgress(MaiBotUpdatePhase.Checking, "正在通过 GitHub 镜像获取上游版本...")
            validationError()?.let { error ->
                failCheck(error)
                return@launch
            }

            TerminalLog.append("")
            TerminalLog.append("$LOG_TAG 检查上游版本")
            val result = runCommand(MaiBotUpdateScripts.fetchLatest())
            val report = parseMaiBotUpdateReport(result.output)
            applyReport(report)
            if (!result.success || report.installed == null || report.latest == null) {
                failCheck("获取上游版本失败：${result.summary()}")
                return@launch
            }

            val phase = if (report.hasUpdate) {
                MaiBotUpdatePhase.Available
            } else {
                MaiBotUpdatePhase.UpToDate
            }
            TerminalLog.append(
                if (report.hasUpdate) {
                    "$LOG_TAG 发现新版本 ${report.latest.shortCommit}，当前 ${report.installed.shortCommit}"
                } else {
                    "$LOG_TAG 已是最新版本 ${report.installed.shortCommit}"
                }
            )
            _state.update {
                it.copy(phase = phase, progressText = null, errorMessage = null, failedAction = null)
            }
            // 启动检查查到新版本：主页弹窗提醒，用户点“更新并重启”就地更新
            if (report.hasUpdate && checkingOnLaunch) {
                TerminalLog.append("$LOG_TAG 弹窗提醒更新")
                _launchPrompt.value = true
            }
        }
    }

    // 检查这一步失败：用户自己点的检查照常报错；启动时的自动检查只记日志、退回空闲，不在设置页挂一条没人问过的失败。
    private fun failCheck(message: String) {
        if (!checkingOnLaunch) {
            fail(MaiBotUpdateAction.Check, message)
            return
        }
        TerminalLog.append("$LOG_TAG 启动时检查更新没有成功：$message")
        _state.update { it.copy(phase = MaiBotUpdatePhase.Idle, progressText = null) }
    }

    /**
     * 应用更新：停止麦麦 -> 拉取并切换到上游 -> uv sync -> 重启麦麦。
     * 停止期间进程管理器会拦下唤醒、开机自启等启动请求，避免更新到一半麦麦被拉起。
     * 无论成败最后都会重新拉起麦麦：拉取失败时源码没动，依赖同步失败时旧的 .venv 通常仍能运行。
     */
    fun applyUpdate() {
        if (job?.isActive == true) return
        job = scope.launch {
            showProgress(MaiBotUpdatePhase.Updating, "正在停止麦麦...")
            validationError()?.let { error ->
                fail(MaiBotUpdateAction.Apply, error)
                return@launch
            }

            TerminalLog.append("")
            TerminalLog.append("$LOG_TAG 停止 MaiBot，准备切换到上游最新版本")
            val failure = maiBotProcess.runWhileStopped { updateWhileStopped() }

            TerminalLog.append("$LOG_TAG 重新启动 MaiBot")
            restartMaiBot()

            if (failure != null) {
                fail(MaiBotUpdateAction.Apply, failure)
                return@launch
            }
            val installed = _state.value.installed
            TerminalLog.append("$LOG_TAG 更新完成：${installed?.shortCommit.orEmpty()}")
            _state.update {
                it.copy(
                    phase = MaiBotUpdatePhase.Updated,
                    progressText = null,
                    errorMessage = null,
                    failedAction = null
                )
            }
        }
    }

    /** 返回失败原因，成功返回 null。 */
    private suspend fun updateWhileStopped(): String? {
        showProgress(MaiBotUpdatePhase.Updating, "正在拉取上游源码并切换版本...")
        val updateResult = runCommand(MaiBotUpdateScripts.fetchAndReset())
        val report = parseMaiBotUpdateReport(updateResult.output)
        applyReport(report)
        if (!updateResult.success || report.installed == null) {
            return "源码更新失败：${updateResult.summary()}"
        }

        // 源码切换后按 OOBE 同样的三步重装依赖：uv 和 Python 已就绪时脚本会立刻跳过，
        // 兜底处理 uv 被误删、上游改了 Python 版本要求的情况；最后 uv sync 按新的 uv.lock 同步。
        for (step in MaiBotDependencyStep.entries) {
            showProgress(MaiBotUpdatePhase.Updating, step.progressText)
            val result = runCommand(step.script())
            if (!result.success) return step.failureMessage(result)
        }
        return null
    }

    // 更新可能在后台跑完：Android 12+ 不允许后台新拉前台服务，此时服务通常仍在运行，直接让进程管理器启动麦麦。
    private fun restartMaiBot() {
        val serviceStarted = runCatching { MaiBotRuntimeService.start(appContext) }.isSuccess
        if (!serviceStarted) {
            maiBotProcess.start()
        }
    }

    private suspend fun validationError(): String? = withContext(Dispatchers.IO) {
        val containerProblem = readiness.containerProblem()
        when {
            containerProblem != null -> containerProblem
            !readiness.isMaiBotSourceReady() -> "MaiBot 源码尚未安装"
            !paths.maiBotGitDir.exists() -> "MaiBot 目录不是 Git 仓库，无法在线更新"
            else -> null
        }
    }

    private suspend fun runCommand(
        script: ContainerScript,
        logOutput: Boolean = true
    ): ContainerCommandResult =
        commandRunner.run(script) { line ->
            if (logOutput && line.isNotBlank()) TerminalLog.append(line)
        }

    // 脚本即使中途失败也可能已经打印了部分结果（如当前版本），有值的字段都同步进状态。
    private fun applyReport(report: MaiBotUpdateReport) {
        _state.update {
            it.copy(
                branch = report.branch ?: it.branch,
                installed = report.installed ?: it.installed,
                latest = report.latest ?: it.latest,
                localChangeCount = report.localChangeCount ?: it.localChangeCount
            )
        }
    }

    private fun showProgress(phase: MaiBotUpdatePhase, text: String) {
        _state.update {
            it.copy(phase = phase, progressText = text, errorMessage = null, failedAction = null)
        }
    }

    private fun fail(action: MaiBotUpdateAction, message: String) {
        TerminalLog.append("$LOG_TAG 失败：$message")
        _state.update {
            it.copy(
                phase = MaiBotUpdatePhase.Failed,
                progressText = null,
                errorMessage = message,
                failedAction = action
            )
        }
    }
}
