package org.maiwithu.maidroid.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.terminal.TerminalLog

/**
 * 进程一启动就订阅 TerminalLog 的逐行广播，交给 TerminalLogFiles 写进日志文件。
 *
 * 由 AppDependencies 在 Application.onCreate 里立即创建：订阅用 UNDISPATCHED 当场挂上，
 * 之后写文件都在只允许一个并发的 IO 调度器上按先后顺序执行，不拖慢写终端输出的线程，TerminalLogFiles 也不用加锁。
 * 第一行先写一行启动标记（版本号和进程号），看日志时能分清是哪一次启动：它比订阅先排进队列，所以一定在最前面。
 */
internal class TerminalLogRecorder(
    private val files: TerminalLogFiles,
    startBanner: String
) {
    private val scope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    init {
        scope.launch { files.append(startBanner) }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            TerminalLog.lines.collect { line -> files.append(line) }
        }
    }

    /** 日志目录里的全部终端日志，最新的在前。 */
    fun recentFiles() = files.recentFiles()
}
