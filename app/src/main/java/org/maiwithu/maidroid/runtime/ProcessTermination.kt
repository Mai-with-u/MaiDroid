package org.maiwithu.maidroid.runtime

import java.util.concurrent.TimeUnit

/** 强杀宿主侧的 proot 进程后，最多等多久让它被回收。 */
private const val PROOT_KILL_WAIT_MS = 3_000L

/**
 * 强杀宿主侧的 proot 进程（SIGKILL），最多等 PROOT_KILL_WAIT_MS 让它被回收。阻塞调用，要放在 IO 线程。
 * 返回 true 表示进程当时还活着、是被这里杀掉的，调用方可以据此记一行日志。
 *
 * 这只是兜底，停止长驻进程时要先用 requestExitScript 在容器里请进程自己退出：
 * proot 会忽略 SIGTERM（2026-11 真机实测），信号到不了容器里的进程，所以这里也不先发 SIGTERM 白等；
 * proot 被杀后容器里的进程不会跟着结束，而是脱离 proot 接着跑，调用方之后都要按进程名清理残留。
 */
internal fun Process.killProot(): Boolean =
    runCatching {
        if (isAlive) {
            destroyForcibly()
            waitFor(PROOT_KILL_WAIT_MS, TimeUnit.MILLISECONDS)
            true
        } else {
            false
        }
    }.getOrDefault(false)

/** 停止一个长驻进程后它最后是怎么停下来的：本来就没在跑、自己正常退出、没有自己退出而被强制停止。 */
internal enum class ProcessExitOutcome {
    NotRunning,
    Exited,
    ForceStopped
}
