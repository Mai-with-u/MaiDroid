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
 * 这次打开主界面要不要先自动升级 MaiBot：MaiDroid 刚更新过（上次打开时的版本号比现在小）、
 * OOBE 早已完成（新装的走 OOBE，装的就是最新版）、用户没在设置页打开“关闭 MaiBot 自动更新”。
 */
internal fun shouldAutoUpdateMaiBot(
    setupComplete: Boolean,
    lastLaunchedVersionCode: Int,
    currentVersionCode: Int,
    autoUpdateDisabled: Boolean
): Boolean = setupComplete && !autoUpdateDisabled && lastLaunchedVersionCode < currentVersionCode

// MaiDroid 更新后自动升级 MaiBot 的结果：没做成（不算失败，照常启动）、已是最新、升级完成、升级到一半失败。
private sealed interface AutoUpdateOutcome {
    data class Skipped(val reason: String) : AutoUpdateOutcome
    data object UpToDate : AutoUpdateOutcome
    data object Updated : AutoUpdateOutcome
    data class Failed(val reason: String) : AutoUpdateOutcome
}

/**
 * MaiBot 在线更新：检查上游 -> 停止麦麦 -> 切换源码 -> 同步依赖 -> 重启麦麦。
 *
 * 进程级单例，切到后台或关掉设置页后更新照常进行，回到设置页能看到最新进度。
 * 每次打开应用进入主页时主界面会调 [checkOnLaunch] 自动查一次，查到新版本就弹窗提醒；
 * MaiDroid 更新后的第一次启动改调 [updateAfterAppUpgrade]，在唤醒页上直接升级。
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
        // 写进终端输出（TerminalLog）的行首前缀，不是 logcat 标签。
        private const val LOG_PREFIX = "[MaiBot 更新]"
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
            TerminalLog.append("$LOG_PREFIX 检查上游版本")
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
                    "$LOG_PREFIX 发现新版本 ${report.latest.shortCommit}，当前 ${report.installed.shortCommit}"
                } else {
                    "$LOG_PREFIX 已是最新版本 ${report.installed.shortCommit}"
                }
            )
            _state.update {
                it.copy(phase = phase, progressText = null, errorMessage = null, failedAction = null)
            }
            // 启动检查查到新版本：主页弹窗提醒，用户点“更新并重启”就地更新
            if (report.hasUpdate && checkingOnLaunch) {
                TerminalLog.append("$LOG_PREFIX 弹窗提醒更新")
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
        TerminalLog.append("$LOG_PREFIX 启动时检查更新没有成功：$message")
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
            TerminalLog.append("$LOG_PREFIX 停止 MaiBot，准备切换到上游最新版本")
            val failure = maiBotProcess.runWhileStopped { updateWhileStopped() }

            TerminalLog.append("$LOG_PREFIX 重新启动 MaiBot")
            restartMaiBot()

            if (failure != null) {
                fail(MaiBotUpdateAction.Apply, failure)
                return@launch
            }
            val installed = _state.value.installed
            TerminalLog.append("$LOG_PREFIX 更新完成：${installed?.shortCommit.orEmpty()}")
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

    /**
     * MaiDroid 更新后第一次启动时自动升级 MaiBot，唤醒页上显示进度；挂起到整个过程结束
     * （过程本身在更新管理器自己的作用域里跑，界面关掉也会做完）。
     * 全程拦住麦麦的启动（唤醒页的自动唤醒、服务的启动请求都不会把它拉起来）：
     * 拉取上游 -> 有新版本或源码被改过就还原改动、切到上游、重装依赖 -> 放开并拉起麦麦。
     * 已是最新就直接拉起；拉取失败不打扰，照常拉起现在的版本，之后的启动检查和设置页里都还能更新；
     * 更新失败时弹出 MaiBot 更新弹窗说明原因，可以重试。
     */
    suspend fun updateAfterAppUpgrade() {
        if (job?.isActive == true) return
        checkingOnLaunch = false
        val updateJob = scope.launch {
            showProgress(MaiBotUpdatePhase.Checking, "MaiDroid 已更新，正在检查 MaiBot 新版本...")
            TerminalLog.append("")
            TerminalLog.append("$LOG_PREFIX MaiDroid 已更新，启动麦麦前先把 MaiBot 升级到上游最新版本")
            // 先进入维护再做任何耗时操作：这一句在启动时同步执行，唤醒页随后发来的启动请求都会被拦下
            val outcome = maiBotProcess.runWhileStopped { autoUpdateWhileStopped() }

            TerminalLog.append("$LOG_PREFIX 启动 MaiBot")
            restartMaiBot()

            when (outcome) {
                is AutoUpdateOutcome.Skipped -> {
                    TerminalLog.append("$LOG_PREFIX 这次没有自动升级（${outcome.reason}），照常启动现在的版本")
                    _state.update { it.copy(phase = MaiBotUpdatePhase.Idle, progressText = null) }
                }
                AutoUpdateOutcome.UpToDate -> {
                    TerminalLog.append("$LOG_PREFIX 已是最新版本 ${_state.value.installed?.shortCommit.orEmpty()}")
                    _state.update {
                        it.copy(phase = MaiBotUpdatePhase.UpToDate, progressText = null, errorMessage = null)
                    }
                }
                AutoUpdateOutcome.Updated -> {
                    TerminalLog.append("$LOG_PREFIX 自动升级完成：${_state.value.installed?.shortCommit.orEmpty()}")
                    _state.update {
                        it.copy(
                            phase = MaiBotUpdatePhase.Updated,
                            progressText = null,
                            errorMessage = null,
                            failedAction = null
                        )
                    }
                }
                // 升级到一半失败：弹出 MaiBot 更新弹窗，说明原因并给“重试更新”
                is AutoUpdateOutcome.Failed -> {
                    fail(MaiBotUpdateAction.Apply, outcome.reason)
                    _launchPrompt.value = true
                }
            }
        }
        job = updateJob
        updateJob.join()
    }

    // 自动升级在维护期间做的事：检查 -> （需要时）还原并切到上游、重装依赖。拉取失败、已是最新且没改过源码时不动源码。
    private suspend fun autoUpdateWhileStopped(): AutoUpdateOutcome {
        validationError()?.let { return AutoUpdateOutcome.Skipped(it) }
        val result = runCommand(MaiBotUpdateScripts.fetchLatest())
        val report = parseMaiBotUpdateReport(result.output)
        applyReport(report)
        if (!result.success || report.installed == null || report.latest == null) {
            return AutoUpdateOutcome.Skipped("获取上游版本失败：${result.summary()}")
        }
        // 已是最新但源码被改过时也还原一次：MaiDroid 不支持在手机上改源码，误改的文件趁这次恢复
        if (!report.hasUpdate && (report.localChangeCount ?: 0) == 0) return AutoUpdateOutcome.UpToDate
        TerminalLog.append(
            if (report.hasUpdate) {
                "$LOG_PREFIX 发现新版本 ${report.latest.shortCommit}，当前 ${report.installed.shortCommit}，开始升级"
            } else {
                "$LOG_PREFIX 已是最新版本，但有 ${report.localChangeCount} 个源码文件被改动过，还原为上游版本"
            }
        )
        return updateWhileStopped()?.let { AutoUpdateOutcome.Failed(it) } ?: AutoUpdateOutcome.Updated
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
        TerminalLog.append("$LOG_PREFIX 失败：$message")
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
