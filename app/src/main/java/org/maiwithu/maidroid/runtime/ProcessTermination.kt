package org.maiwithu.maidroid.runtime

import java.util.concurrent.TimeUnit

/** 结束容器里的长驻进程（MaiBot、NapCat、sshd）时，给宿主侧的 proot 进程留多久自己退出。 */
internal const val GRACEFUL_STOP_TIMEOUT_MS = 3_000L

/**
 * 先 SIGTERM，最多等 GRACEFUL_STOP_TIMEOUT_MS，仍未退出再 SIGKILL。
 * 阻塞调用，要放在 IO 线程。返回 true 表示最后是被强杀的，调用方可以据此记一行日志。
 *
 * 注意这里的进程是宿主侧的 proot，而 proot 会忽略 SIGTERM（2026-11 真机实测），所以容器里的进程
 * 收不到这个信号，实际效果是等 3 秒后强杀 proot；之后各监管器还会按进程名清理残留。
 * 要让进程自己收尾、保存数据，得在容器里直接给它发信号，见 requestExitScript（安全退出用它）。
 */
internal fun Process.terminateGracefully(): Boolean =
    runCatching {
        destroy()
        waitFor(GRACEFUL_STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (isAlive) {
            destroyForcibly()
            true
        } else {
            false
        }
    }.getOrDefault(false)

/** 安全退出时一个长驻进程最后是怎么停下来的：本来就没在跑、自己正常退出、超时没退出而被强制停止。 */
internal enum class ProcessExitOutcome {
    NotRunning,
    Exited,
    ForceStopped
}
