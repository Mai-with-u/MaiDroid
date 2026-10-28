package org.maiwithu.maidroid.log

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 全应用共享的“终端输出”：MaiBot、NapCat、sshd、OOBE、在线更新的输出都写到这里。
 *
 * 主页右下角的终端按钮展示最近的 MAX_LOG_LINES 行；每行同时打到 logcat 的 TerminalLog 标签，
 * 真机排查时 `adb logcat -s TerminalLog` 就能拿到完整输出。
 *
 * 进程输出里的 ANSI 转义序列在入库前由 AnsiText 处理：光标移动、擦除这类序列按终端语义执行掉，
 * 只把 SGR 颜色留在 logs 里给终端面板上色；logcat 和 lines 拿到的是去掉全部转义序列的纯文本。
 */
object TerminalLog {
    private const val TAG = "TerminalLog"
    private const val MAX_LOG_LINES = 800

    private val _logs = MutableStateFlow<List<String>>(emptyList())

    /** 最近 MAX_LOG_LINES 行，终端弹窗直接展示。行里还带着 SGR 颜色序列，要按文字匹配时先用 AnsiText.plain 转成纯文本。 */
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // 逐行广播新日志的纯文本，给要靠日志判断状态的模块（如 QQ 官方机器人的连接报错）实时订阅；不重放历史，
    // 订阅方需要历史时自己读 logs。订阅方处理不过来时丢最旧的行，绝不阻塞写日志的线程。
    private val _lines = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    // MaiBot、NapCat、SSH、在线更新会在不同线程同时写日志，用 CAS 更新避免互相覆盖丢行。
    fun append(line: String) {
        // 先解析转义序列：MaiBot 提示符重绘留下的 ESC[2D、ESC[J 等在这里执行掉，括号粘贴、显隐光标这类开关直接丢弃。
        // 入库的行只保留 SGR 颜色；订阅方和 logcat 用纯文本，免得关键字被颜色代码打断。解析放在 update 外面，CAS 重试时不重复做。
        val parsed = AnsiText.parse(line)
        val stored = parsed.toSgrString()
        _logs.update { (it + stored).takeLast(MAX_LOG_LINES) }
        _lines.tryEmit(parsed.text)
        Log.d(TAG, parsed.text)
    }
}
