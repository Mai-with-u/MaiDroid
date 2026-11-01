package org.maiwithu.maidroid.maibot

import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.MAIBOT_RUNTIME_CAUSES
import org.maiwithu.maidroid.diagnostics.diagnoseFailureCause

/**
 * 拉起 MaiBot 后等它的 WebUI 能访问时的判断规则。OOBE 每秒看一次进程状态和 WebUI 探测结果，交给
 * maiBotStartupVerdict：返回 null 表示继续等，否则就是结论。
 *
 * 以前只等 3 分钟有没有 HTTP 响应：MaiBot 起来就崩时也要空等满 3 分钟，最后只报一句“启动超时”；
 * 低端机首次启动超过 3 分钟又会被误判成失败。现在进程反复退出、根本没拉起来都会立刻报出原因，
 * 进程活着就最多等 10 分钟。
 */
internal object MaiBotStartup {
    /** 意外退出达到这个次数就判定起不来。允许一次：MaiBot 首次启动可能自己退出一次请求重启。 */
    const val MAX_EXITS = 2

    /** 请求启动后这么久进程还没起来、也没退出过，就判定根本没拉起来。 */
    const val NOT_STARTED_GRACE_MS = 60_000L

    /** 进程一直活着时最多等这么久。 */
    const val TIMEOUT_MS = 10 * 60_000L
}

/** 等 MaiBot WebUI 的结论。 */
internal sealed interface MaiBotStartupResult {
    data object Ready : MaiBotStartupResult

    /** 启动前检查没通过，监管器没有拉起进程。 */
    data class Blocked(val reason: String) : MaiBotStartupResult

    /** 进程起来后反复退出。 */
    data class Crashed(val exits: Int, val lastExitCode: Int?) : MaiBotStartupResult

    /** 请求启动后进程一直没起来。 */
    data object NotStarted : MaiBotStartupResult

    /** 进程活着，但一直等不到 WebUI。 */
    data object TimedOut : MaiBotStartupResult
}

/**
 * baselineExitCount 是请求启动前 status.exitCount 的值，只看这之后的退出；
 * WebUI 探测通了还要进程确实在跑，免得别的程序占着同一个端口被当成 MaiBot。
 */
internal fun maiBotStartupVerdict(
    status: MaiBotProcessStatus,
    baselineExitCount: Int,
    webUiReachable: Boolean,
    elapsedMs: Long
): MaiBotStartupResult? {
    val exits = status.exitCount - baselineExitCount
    return when {
        webUiReachable && status.running -> MaiBotStartupResult.Ready
        status.launchBlockedReason != null -> MaiBotStartupResult.Blocked(status.launchBlockedReason)
        exits >= MaiBotStartup.MAX_EXITS -> MaiBotStartupResult.Crashed(exits, status.lastExitCode)
        !status.running && exits == 0 && elapsedMs >= MaiBotStartup.NOT_STARTED_GRACE_MS ->
            MaiBotStartupResult.NotStarted
        elapsedMs >= MaiBotStartup.TIMEOUT_MS -> MaiBotStartupResult.TimedOut
        else -> null
    }
}

/** 失败时给界面的一句话；Ready 返回 null。 */
internal fun MaiBotStartupResult.failureMessage(): String? = when (this) {
    MaiBotStartupResult.Ready -> null
    is MaiBotStartupResult.Blocked -> "MaiBot 没有启动：$reason"
    is MaiBotStartupResult.Crashed ->
        "MaiBot 启动后连续退出了 $exits 次（最后退出码 ${lastExitCode ?: "未知"}），请查看终端输出里的报错"
    MaiBotStartupResult.NotStarted -> "MaiBot 进程一直没有启动起来，请查看终端输出"
    MaiBotStartupResult.TimedOut ->
        "MaiBot 已运行 ${MaiBotStartup.TIMEOUT_MS / 60_000} 分钟，WebUI 仍无法访问，请查看终端输出"
}

/**
 * 失败时带错误码的结论；Ready 返回 null。recentOutput 是 MaiBot 最近几次运行的输出（maiBotRecentRunOutput），
 * 反复退出、等不到 WebUI 时从里面认原因，只认会让 MaiBot 起不来的几类（MAIBOT_RUNTIME_CAUSES）。
 */
internal fun MaiBotStartupResult.failure(recentOutput: String): Failure? {
    val message = failureMessage() ?: return null
    val step = when (this) {
        MaiBotStartupResult.Ready -> return null
        is MaiBotStartupResult.Blocked -> return Failure(FailureStep.MaiBotBlocked, message)
        is MaiBotStartupResult.Crashed -> FailureStep.MaiBotCrash
        MaiBotStartupResult.NotStarted -> return Failure(FailureStep.MaiBotNotStarted, message)
        MaiBotStartupResult.TimedOut -> FailureStep.MaiBotTimeout
    }
    return Failure(step, message, diagnoseFailureCause(recentOutput, among = MAIBOT_RUNTIME_CAUSES))
}

/**
 * 从终端日志（纯文本行）里取 MaiBot 最近 runs 次运行的输出：从倒数第 runs 个“Process started”起到最后。
 * 反复退出时最后一次可能刚被重新拉起、还没输出什么，所以默认看两次。日志里没有启动记录时返回全部。
 */
internal fun maiBotRecentRunOutput(lines: List<String>, runs: Int = 2): String {
    val starts = lines.indices.filter { lines[it].startsWith(MaiBotProcessSupervisor.PROCESS_STARTED_LOG) }
    val from = starts.getOrNull(starts.size - runs) ?: starts.firstOrNull() ?: 0
    return lines.drop(from).joinToString("\n")
}
