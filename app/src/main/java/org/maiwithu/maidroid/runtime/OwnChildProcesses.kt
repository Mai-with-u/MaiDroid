package org.maiwithu.maidroid.runtime

import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

/**
 * 宿主机侧进程巡检与清理：直接读取同 UID 在 /proc 下的进程目录，
 * 避免为了数进程或清残留又专门拉起一次 proot（每次拉起会额外消耗幻影进程名额和 SELinux 审计开销）。
 */

private val WHITESPACE = Regex("\\s+")
private const val MIN_CPU_SAMPLE_WINDOW_MS = 10_000L
private const val MAX_REPORTED_TREE_CPU_PCT = 1600.0

/** 单个进程的启动节拍（区分同 PID 复用）与累计用户态/内核态 CPU 节拍数。 */
internal data class ProcessCpuTicks(
    val startTimeTicks: Long,
    val cpuTicks: Long
)

/**
 * 解析 Linux `/proc/<pid>/stat` 单行里的 `utime + stime` 与 `starttime`。
 * 进程名放在一对圆括号里且允许带空格或括号，必须以最后一个 `)` 定位后续字段偏移。
 */
internal fun parseProcStatCpuTicks(statLine: String): ProcessCpuTicks? {
    val rparen = statLine.lastIndexOf(')')
    if (rparen < 0 || rparen + 2 >= statLine.length) return null
    val tokens = statLine.substring(rparen + 1).trim().split(WHITESPACE)
    if (tokens.size < 20) return null
    val utime = tokens[11].toLongOrNull() ?: return null
    val stime = tokens[12].toLongOrNull() ?: return null
    val startTime = tokens[19].toLongOrNull() ?: return null
    return ProcessCpuTicks(startTimeTicks = startTime, cpuTicks = utime + stime)
}

/** 采集宿主主进程与同 UID 全部容器子进程当前的 CPU 累计节拍，供周期窗口计算整树占用率。 */
internal fun readOwnTreeCpuTicks(
    procRoot: File = File("/proc"),
    myUid: Int = AndroidProcess.myUid(),
    myPid: Int = AndroidProcess.myPid()
): Map<Int, ProcessCpuTicks> {
    val result = HashMap<Int, ProcessCpuTicks>()
    readPidStatCpuTicks(File(procRoot, myPid.toString()))?.let { result[myPid] = it }
    for ((pid, dir) in listOwnProcessDirs(procRoot, myUid, myPid)) {
        readPidStatCpuTicks(dir)?.let { result[pid] = it }
    }
    return result
}

private fun readPidStatCpuTicks(procDir: File): ProcessCpuTicks? =
    runCatching {
        val statFile = File(procDir, "stat")
        if (!statFile.isFile) return@runCatching null
        parseProcStatCpuTicks(statFile.readText(Charsets.UTF_8))
    }.getOrNull()

/**
 * 根据前后两次采样计算本应用整棵进程树（含 proot / MaiBot / QQ / Xvfb）在采样窗口内的单核等效 CPU 占用（%）。
 * `elapsedMs` 小于 10 秒或任一侧为空快照时返回 null，避免短暂窗口或冷启基线期算虚高。
 */
internal fun computeTreeCpuPercent(
    previous: Map<Int, ProcessCpuTicks>,
    current: Map<Int, ProcessCpuTicks>,
    elapsedMs: Long,
    clockTicksPerSec: Long = 100L
): Double? {
    if (previous.isEmpty() || current.isEmpty() || elapsedMs < MIN_CPU_SAMPLE_WINDOW_MS || clockTicksPerSec <= 0L) {
        return null
    }
    var deltaTicks = 0L
    for ((pid, curr) in current) {
        val prev = previous[pid]
        deltaTicks += if (prev != null && prev.startTimeTicks == curr.startTimeTicks) {
            (curr.cpuTicks - prev.cpuTicks).coerceAtLeast(0L)
        } else {
            curr.cpuTicks.coerceAtLeast(0L)
        }
    }
    val deltaCpuSec = deltaTicks.toDouble() / clockTicksPerSec.toDouble()
    val wallSec = elapsedMs.toDouble() / 1000.0
    val pct = (deltaCpuSec / wallSec) * 100.0
    return ((pct * 10.0).roundToInt() / 10.0).coerceIn(0.0, MAX_REPORTED_TREE_CPU_PCT)
}

/**
 * 统计当前应用名下（同 UID，排除主进程自身）存活的原生子进程总数，
 * 供容器收到 SIGKILL(137) 后与诊断报告定位是否逼近系统的幻影进程上限。
 */
internal fun countOwnChildProcesses(
    procRoot: File = File("/proc"),
    myUid: Int = AndroidProcess.myUid(),
    myPid: Int = AndroidProcess.myPid()
): Int = listOwnProcessDirs(procRoot, myUid, myPid).size

/**
 * 容器非主动停止却以 SIGKILL(137) 退出时打一行本轮运行时长与进程水位日志，
 * 便于现场分辨是刚启动即死、幻影进程上限还是后台长跑强杀。
 */
internal fun logUnexpectedSigkill(
    exitCode: Int,
    intentionallyStopped: Boolean,
    uptimeMs: Long? = null,
    childCountProvider: () -> Int = ::countOwnChildProcesses,
    onLog: (String) -> Unit
) {
    if (exitCode == 137 && !intentionallyStopped) {
        val uptimeNote = uptimeMs?.takeIf { it >= 0L }?.let { "，本轮已运行 ${it / 1000L}s" }.orEmpty()
        onLog("[MaiDroid] 容器进程收到 SIGKILL(137)$uptimeNote，当前本应用子进程数：${childCountProvider()}")
    }
}

/**
 * 按命令行正则结束本应用名下遗留的子进程：先发 SIGTERM 让它们有机会收尾 ->
 * 有命中时挂起等一秒（不阻塞 IO 线程池）再扫一遍，对仍存活且匹配的进程补发 SIGKILL 强制清场。
 * 不经过 proot/ps/awk，零幻影进程脉冲。
 */
internal suspend fun killOwnMatchingProcesses(
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
    if (signalDelayMs > 0L) delay(signalDelayMs)

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
