package org.maiwithu.maidroid.process

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object TerminalLogRepository {
    private const val TAG = "TerminalLog"
    private const val MAX_LOG_LINES = 800

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    // MaiBot、NapCat、SSH、在线更新会在不同线程同时写日志，用 CAS 更新避免互相覆盖丢行。
    fun append(line: String) {
        _logs.update { (it + line).takeLast(MAX_LOG_LINES) }
        Log.d(TAG, line)
    }

    fun appendCommand(command: List<String>) {
        append("")
        append("$ ${command.joinToString(" ")}")
    }
}
