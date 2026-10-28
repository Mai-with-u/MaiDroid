package org.maiwithu.maidroid.process

import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TerminalLogRepository {
    private const val TAG = "TerminalLog"
    private const val MAX_LOG_LINES = 800

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // 逐行广播新日志，给要靠日志判断状态的模块（如 QQ 官方机器人的连接报错）实时订阅；不重放历史，
    // 订阅方需要历史时自己读 logs。订阅方处理不过来时丢最旧的行，绝不阻塞写日志的线程。
    private val _lines = MutableSharedFlow<String>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val lines: SharedFlow<String> = _lines.asSharedFlow()

    // MaiBot、NapCat、SSH、在线更新会在不同线程同时写日志，用 CAS 更新避免互相覆盖丢行。
    fun append(line: String) {
        _logs.update { (it + line).takeLast(MAX_LOG_LINES) }
        _lines.tryEmit(line)
        Log.d(TAG, line)
    }

    fun appendCommand(command: List<String>) {
        append("")
        append("$ ${command.joinToString(" ")}")
    }
}
