package org.maiwithu.maidroid.process

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TerminalLogRepository {
    private const val TAG = "TerminalLog"
    private const val MAX_LOG_LINES = 800

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun append(line: String) {
        _logs.value = (_logs.value + line).takeLast(MAX_LOG_LINES)
        Log.d(TAG, line)
    }

    fun appendCommand(command: List<String>) {
        append("")
        append("$ ${command.joinToString(" ")}")
    }
}
