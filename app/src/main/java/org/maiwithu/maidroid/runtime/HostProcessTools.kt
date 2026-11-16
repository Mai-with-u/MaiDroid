package org.maiwithu.maidroid.runtime

import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * 宿主机侧进程巡检与清理工具：直接读取同 UID 在 /proc 下的进程目录，
 * 避免为了数进程或清残留又专门拉起一次 proot（每次拉起会额外消耗幻影进程名额和 SELinux 审计开销）。
 */

private val WHITESPACE = Regex("\\s+")

/**
 * 统计当前应用名下（同 UID，排除主进程自身）存活的原生子进程总数，
 * 供容器收到 SIGKILL(137) 后与诊断报告定位是否逼近系统的幻影进程上限。
 */
internal fun countOwnChildProcesses(
    procRoot: File = File("/proc"),
    myUid: Int = AndroidProcess.myUid(),
    myPid: Int = AndroidProcess.myPid()
): Int = listOwnProcessDirs(procRoot, myUid, myPid).size

/** 容器非主动停止却以 SIGKILL(137) 退出时打一行进程水位日志，便于现场判断是幻影上限还是其他系统强杀。 */
internal fun logUnexpectedSigkill(
    exitCode: Int,
    intentionallyStopped: Boolean,
    onLog: (String) -> Unit
) {
    if (exitCode == 137 && !intentionallyStopped) {
        onLog("[MaiDroid] 容器进程收到 SIGKILL(137)，当前本应用子进程数：${countOwnChildProcesses()}")
    }
}

/**
 * 按命令行正则结束本应用名下遗留的子进程：先发 SIGTERM 让它们有机会收尾 ->
 * 有命中时等一秒再扫一遍，对仍存活且匹配的进程补发 SIGKILL 强制清场。
 * 不经过 proot/ps/awk，零幻影进程脉冲。
 */
internal fun killOwnMatchingProcesses(
    pattern: Regex,
    processName: String,
    logPrefix: String = "",
    procRoot: File = File("/proc"),
    myUid: Int = AndroidProcess.myUid(),
    myPid: Int = AndroidProcess.myPid(),
    signalDelayMs: Long = 1_000L,
    signalSender: (Int, Int) -> Unit = { pid, signal -> Os.kill(pid, signal) },
    onLog: (String) -> Unit = {}
) {
    val firstTargets = findMatchingPids(procRoot, myUid, myPid, pattern)
    if (firstTargets.isEmpty()) return

    firstTargets.forEach { pid ->
        onLog("${logPrefix}stopping $processName $pid")
        runCatching { signalSender(pid, OsConstants.SIGTERM) }
    }
    if (signalDelayMs > 0L) Thread.sleep(signalDelayMs)

    val remainingTargets = findMatchingPids(procRoot, myUid, myPid, pattern)
    remainingTargets.forEach { pid ->
        onLog("${logPrefix}force stopping $processName $pid")
        runCatching { signalSender(pid, OsConstants.SIGKILL) }
    }
}

private fun findMatchingPids(
    procRoot: File,
    myUid: Int,
    myPid: Int,
    pattern: Regex
): List<Int> = listOwnProcessDirs(procRoot, myUid, myPid).mapNotNull { (pid, dir) ->
    val cmdline = readProcessCmdline(dir) ?: return@mapNotNull null
    pid.takeIf { pattern.containsMatchIn(cmdline) }
}

/** 列出 /proc 下属于指定 UID 且不是调用进程自身的数字目录，读不到权限（其他应用）或中途退出的跳过。 */
internal fun listOwnProcessDirs(
    procRoot: File,
    myUid: Int,
    myPid: Int
): List<Pair<Int, File>> {
    val entries = procRoot.listFiles() ?: return emptyList()
    return entries.mapNotNull { dir ->
        if (!dir.isDirectory) return@mapNotNull null
        val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
        if (pid <= 0 || pid == myPid) return@mapNotNull null
        val uid = readProcessRealUid(File(dir, "status")) ?: return@mapNotNull null
        if (uid != myUid) return@mapNotNull null
        pid to dir
    }.sortedBy { it.first }
}

private fun readProcessRealUid(statusFile: File): Int? =
    runCatching {
        if (!statusFile.isFile) return@runCatching null
        statusFile.useLines { lines ->
            val uidLine = lines.firstOrNull { it.startsWith("Uid:") } ?: return@useLines null
            uidLine.trim().split(WHITESPACE).getOrNull(1)?.toIntOrNull()
        }
    }.getOrNull()

private fun readProcessCmdline(procDir: File): String? =
    runCatching {
        val raw = File(procDir, "cmdline").takeIf { it.isFile }?.readBytes() ?: return@runCatching null
        if (raw.isEmpty()) return@runCatching null
        // /proc/<pid>/cmdline 各参数以 NUL 分隔；合并成空格文本，方便与 PS args 同一套正则匹配
        String(raw, Charsets.UTF_8)
            .replace('\u0000', ' ')
            .trim()
            .takeIf { it.isNotEmpty() }
    }.getOrNull()
