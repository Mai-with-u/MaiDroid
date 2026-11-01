package org.maiwithu.maidroid.diagnostics

import java.io.File

/**
 * 出错诊断用到的文件在宿主机（应用私有目录 filesDir/diagnostics）上的位置。
 * 只描述“放在哪”，写入和清理由 TerminalLogFiles、CrashRecorder 负责。
 */
internal class DiagnosticsFiles(appFilesDir: File) {
    val rootDir: File = File(appFilesDir, "diagnostics")

    /** 终端输出的日志文件，每次进程启动一个，见 TerminalLogFiles。 */
    val logsDir: File = File(rootDir, "logs")

    /** MaiDroid 自己闪退时写下的堆栈，见 CrashRecorder。 */
    val crashesDir: File = File(rootDir, "crashes")
}
