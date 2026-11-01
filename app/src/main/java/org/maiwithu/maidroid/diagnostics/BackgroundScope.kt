package org.maiwithu.maidroid.diagnostics

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.maiwithu.maidroid.terminal.TerminalLog

// 记进终端日志的调用栈最多几行：够看出是哪里抛的，又不刷屏
private const val STACK_LINES = 8

/**
 * 进程级管理器（OOBE、装平台、在线更新、进程监管……）的后台作用域都用这个建：SupervisorJob + 只记日志的异常处理器。
 *
 * 这些作用域里没接住的异常默认会让整个 MaiDroid 闪退，前台服务、MaiBot、NapCat 跟着一起被结束，QQ 还要重新扫码；
 * 读写文件出错、存储满、系统服务抛异常这类意外不值得付这个代价。出错时：logcat 记完整调用栈 ->
 * 终端日志记一行说明和前几行调用栈（诊断包能带上）-> 这一个协程结束，同一作用域里的其他协程和整个应用照常运行。
 * 各处还是要自己接住预料得到的失败、给出失败提示；这里只兜住没想到的。
 */
internal fun backgroundScope(name: String, dispatcher: CoroutineDispatcher): CoroutineScope =
    CoroutineScope(
        dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, error ->
            Log.e("MaiDroid", "$name: uncaught exception", error)
            TerminalLog.append("[MaiDroid] $name 出了意外错误（应用没有退出）：${error.javaClass.simpleName}: ${error.message.orEmpty()}")
            error.stackTrace.take(STACK_LINES).forEach { TerminalLog.append("    at $it") }
        }
    )
