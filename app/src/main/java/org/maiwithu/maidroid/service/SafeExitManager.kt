package org.maiwithu.maidroid.service

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.maibot.MaiBotProcessSupervisor
import org.maiwithu.maidroid.platform.napcat.NapCatProcessSupervisor
import org.maiwithu.maidroid.runtime.ProcessExitOutcome
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.ssh.ContainerSshManager
import org.maiwithu.maidroid.terminal.TerminalLog

/** 安全退出要停的长驻服务，按退出进度里的显示顺序排列。 */
internal enum class SafeExitService(val label: String) {
    MaiBot("MaiBot"),
    NapCat("NapCat（QQ）"),
    ContainerSsh("容器 SSH")
}

/** 一项服务的退出进度；done 为 true 的几种都算已经停下。 */
internal enum class SafeExitStage(val done: Boolean) {
    /** 只有 MaiBot 会有：在线更新还在做，做完再请它退出。 */
    WaitingForUpdate(false),
    Stopping(false),
    Exited(true),
    ForceStopped(true),
    NotRunning(true)
}

/** 安全退出的进度：用户确认后 started 变 true，之后只会走到结束（结束时整个进程就关掉了）。 */
internal data class SafeExitState(
    val started: Boolean = false,
    val stages: Map<SafeExitService, SafeExitStage> = emptyMap()
) {
    /** 所有服务都已停下，接下来关闭 MaiDroid。 */
    val finished: Boolean
        get() = started && stages.values.all { it.done }
}

/**
 * 设置页的“安全退出”：让 MaiBot、NapCat、容器 sshd 各自正常退出（MaiBot 保存好记忆和数据、QQ 正常下线），
 * 全部停下后关闭 MaiDroid。
 *
 * 退出由前台服务执行（MaiBotRuntimeService.ACTION_SAFE_EXIT）：用户点了退出马上切走，或者从最近任务里划掉 MaiDroid，
 * 前台服务和唤醒锁仍让进程活着，直到服务都停完；之后服务先停掉自己，再关掉界面、结束进程。
 * 退出一旦开始，各监管器会拦下之后所有的启动请求（唤醒、更新完成后的重启、改时区重启……），服务不会再被拉起来。
 */
internal class SafeExitManager(
    private val settings: AppSettings,
    private val maiBotProcess: MaiBotProcessSupervisor,
    private val napCatProcess: NapCatProcessSupervisor,
    private val containerSsh: ContainerSshManager
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()

    // 唯一的一轮退出；只在 lock 内读写。
    private var exitJob: Deferred<Unit>? = null

    private val _state = MutableStateFlow(SafeExitState())

    /** 退出进度，界面据此显示进度弹窗，前台服务据此刷新常驻通知。 */
    val state: StateFlow<SafeExitState> = _state.asStateFlow()

    /** 用户已经确认安全退出（不管停完没有）。 */
    val started: Boolean
        get() = _state.value.started

    /**
     * 用户在设置页确认安全退出：记下“用户退出了”（开机不再自启）-> 交给前台服务执行退出。
     * 拉不起前台服务时（按钮是在前台点的，正常不会发生）就在这里自己执行，停完直接关闭应用。
     */
    fun request(context: Context) {
        val appContext = context.applicationContext
        settings.userExited = true
        markStarted()
        runCatching { MaiBotRuntimeService.start(appContext, MaiBotRuntimeService.ACTION_SAFE_EXIT) }
            .onFailure {
                scope.launch {
                    run()
                    closeMaiDroid(appContext)
                }
            }
    }

    /** 让所有服务退出，挂起到全部停下；重复调用（比如服务收到第二个退出请求）共用同一轮退出。 */
    suspend fun run() {
        val job = synchronized(lock) {
            exitJob ?: scope.async { stopAll() }.also { exitJob = it }
        }
        job.await()
    }

    // 三项服务同时退出、互不等待：MaiBot 关得最慢（最长约 80 秒），NapCat、sshd 几秒就好，总耗时取决于 MaiBot。
    // 某一项意外出错也记为强制停止、接着关闭应用：进程结束时系统会结束它留下的所有子进程，不会有残留。
    private suspend fun stopAll() {
        markStarted()
        TerminalLog.append("")
        TerminalLog.append("[MaiDroid] 安全退出：通知各服务正常退出，全部停下后关闭 MaiDroid")
        coroutineScope {
            launch {
                stopService(SafeExitService.MaiBot) {
                    maiBotProcess.shutdownForExit { waitingForMaintenance ->
                        val stage = if (waitingForMaintenance) SafeExitStage.WaitingForUpdate else SafeExitStage.Stopping
                        setStage(SafeExitService.MaiBot, stage)
                    }
                }
            }
            launch { stopService(SafeExitService.NapCat) { napCatProcess.shutdownForExit() } }
            launch { stopService(SafeExitService.ContainerSsh) { containerSsh.stopForExit() } }
        }
        TerminalLog.append("[MaiDroid] 所有服务都已停下，关闭 MaiDroid")
    }

    private suspend fun stopService(service: SafeExitService, stop: suspend () -> ProcessExitOutcome) {
        val stage = try {
            when (stop()) {
                ProcessExitOutcome.NotRunning -> SafeExitStage.NotRunning
                ProcessExitOutcome.Exited -> SafeExitStage.Exited
                ProcessExitOutcome.ForceStopped -> SafeExitStage.ForceStopped
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TerminalLog.append("[MaiDroid] 停止 ${service.label} 时出错：${e.message.orEmpty().ifBlank { e.javaClass.simpleName }}")
            SafeExitStage.ForceStopped
        }
        setStage(service, stage)
    }

    private fun markStarted() {
        _state.update { current ->
            if (current.started) {
                current
            } else {
                SafeExitState(
                    started = true,
                    stages = SafeExitService.entries.associateWith { SafeExitStage.Stopping }
                )
            }
        }
    }

    private fun setStage(service: SafeExitService, stage: SafeExitStage) {
        _state.update { it.copy(stages = it.stages + (service to stage)) }
    }
}

/** 一项服务当前阶段给用户看的说明，进度弹窗和常驻通知共用。 */
internal fun SafeExitStage.describe(service: SafeExitService): String =
    when (this) {
        SafeExitStage.WaitingForUpdate -> "正在等在线更新完成，之后再退出"
        SafeExitStage.Stopping -> when (service) {
            SafeExitService.MaiBot -> "正在保存数据并退出"
            SafeExitService.NapCat -> "QQ 正在下线退出"
            SafeExitService.ContainerSsh -> "正在停止"
        }
        SafeExitStage.Exited -> "已正常退出"
        SafeExitStage.ForceStopped -> "没有自己退出，已强制停止"
        SafeExitStage.NotRunning -> "未在运行"
    }

/** 常驻通知的正文：还没停下的第一项在做什么；都停下了就说正在关闭。 */
internal fun safeExitNotificationText(state: SafeExitState): String {
    val pending = SafeExitService.entries.firstOrNull { state.stages[it]?.done == false }
        ?: return "服务都已停下，正在关闭 MaiDroid"
    return "${pending.label}：${state.stages.getValue(pending).describe(pending)}"
}

/**
 * 关闭 MaiDroid：关掉所有界面并从最近任务里移除 -> 结束本进程。只在服务都已停下后调用。
 * 一定要结束进程：各监管器已经永久拦下了启动请求，进程留着的话，下次打开会复用它，麦麦就拉不起来了。
 * 进程结束时系统还会结束它留下的所有子进程，万一有没停干净的容器进程也一并结束。
 */
internal fun closeMaiDroid(context: Context) {
    context.getSystemService(ActivityManager::class.java)?.appTasks?.forEach { task ->
        runCatching { task.finishAndRemoveTask() }
    }
    Process.killProcess(Process.myPid())
}
