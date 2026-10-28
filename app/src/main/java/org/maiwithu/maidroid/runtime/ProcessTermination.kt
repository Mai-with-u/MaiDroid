package org.maiwithu.maidroid.runtime

import java.util.concurrent.TimeUnit

/** 容器里的长驻进程（MaiBot、NapCat、sshd）收到 SIGTERM 后给多久收尾时间。 */
internal const val GRACEFUL_STOP_TIMEOUT_MS = 3_000L

/**
 * 先 SIGTERM 让进程自己收尾，最多等 GRACEFUL_STOP_TIMEOUT_MS，仍未退出再 SIGKILL。
 * 阻塞调用，要放在 IO 线程。返回 true 表示最后是被强杀的，调用方可以据此记一行日志。
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
