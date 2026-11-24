package org.maiwithu.maidroid.platform.snowluma

import android.os.Process as AndroidProcess
import org.maiwithu.maidroid.runtime.listOwnProcessDirs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class ProcLocksSyncOutcome(
    val content: String,
    val wroteFile: Boolean,
    val hasLocks: Boolean
)

/**
 * 宿主侧维护容器 /proc/locks 替身文件：
 * Android 不允许应用进程读系统级 /proc/locks，而 SnowLuma 要靠它识别 QQ 锁着哪个账号的 nt_msg.db。
 * 宿主进程与容器同属一个应用 UID，可直接遍历 /proc/<qq_pid>/fdinfo 里的 lock: 记录并按需原子写入替身文件，
 * 省掉在容器内起一轮 bash/pgrep/grep/sed 子进程带来的幻影进程脉冲；锁内容未变时不落盘，稳定后自动降频到 15 秒一次。
 */
internal object QqProcLocksSync {

    const val ACTIVE_SYNC_INTERVAL_MS = 2_000L
    const val STEADY_SYNC_INTERVAL_MS = 15_000L
    const val STEADY_UNCHANGED_THRESHOLD = 3

    /** 采集同 UID 下所有名为 qq 的进程持有的文件锁行，去掉 "lock:" 前缀恢复为 /proc/locks 格式。 */
    fun collectQqLockLines(
        procRoot: File = File("/proc"),
        myUid: Int = AndroidProcess.myUid(),
        myPid: Int = AndroidProcess.myPid()
    ): List<String> = buildList {
        for ((_, pidDir) in listOwnProcessDirs(procRoot, myUid, myPid)) {
            val comm = runCatching { File(pidDir, "comm").readText().trim() }.getOrNull() ?: continue
            if (comm != "qq") continue
            val fdInfoFiles = runCatching { File(pidDir, "fdinfo").listFiles() }.getOrNull() ?: continue
            fdInfoFiles.sortBy { it.name }
            for (fdFile in fdInfoFiles) {
                runCatching {
                    fdFile.forEachLine { line ->
                        if (line.startsWith("lock:")) {
                            val stripped = line.removePrefix("lock:").trimStart()
                            if (stripped.isNotEmpty()) add(stripped)
                        }
                    }
                }
            }
        }
    }

    /** 采集并在内容变化或目标缺失时才原子写盘，锁状态未变化时直接跳过磁盘 IO 与目录重命名。 */
    fun syncProcLocksSnapshot(
        targetFile: File,
        previousContent: String?,
        procRoot: File = File("/proc"),
        myUid: Int = AndroidProcess.myUid(),
        myPid: Int = AndroidProcess.myPid()
    ): ProcLocksSyncOutcome {
        val lines = collectQqLockLines(procRoot, myUid, myPid)
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
        val hasLocks = lines.isNotEmpty()
        if (previousContent != null && content == previousContent && targetFile.isFile) {
            return ProcLocksSyncOutcome(content = content, wroteFile = false, hasLocks = hasLocks)
        }
        writeAtomically(targetFile, content)
        return ProcLocksSyncOutcome(content = content, wroteFile = true, hasLocks = hasLocks)
    }

    /** 已有账号锁且连续未变化达到阈值后降频为 15 秒轮询，刚启动或发生变动时恢复 2 秒主动探测。 */
    fun nextSyncDelayMs(hasLocks: Boolean, unchangedStreak: Int): Long =
        if (hasLocks && unchangedStreak >= STEADY_UNCHANGED_THRESHOLD) {
            STEADY_SYNC_INTERVAL_MS
        } else {
            ACTIVE_SYNC_INTERVAL_MS
        }

    /** 把采集到的文件锁快照原子写入 targetFile：兼容原有调用入口。 */
    fun writeProcLocksSnapshot(
        targetFile: File,
        procRoot: File = File("/proc"),
        myUid: Int = AndroidProcess.myUid(),
        myPid: Int = AndroidProcess.myPid()
    ) {
        syncProcLocksSnapshot(
            targetFile = targetFile,
            previousContent = null,
            procRoot = procRoot,
            myUid = myUid,
            myPid = myPid
        )
    }

    private fun writeAtomically(targetFile: File, content: String) {
        val parent = targetFile.absoluteFile.parentFile ?: return
        parent.mkdirs()
        val staging = File(parent, "${targetFile.name}.new")
        staging.writeText(content)
        staging.setReadable(true, false)
        staging.setWritable(true, true)
        if (!staging.renameTo(targetFile)) {
            Files.move(staging.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** 清空文件锁替身（不删除文件本身，proot 启动参数仍会绑定它）。 */
    fun clear(targetFile: File) {
        runCatching {
            targetFile.parentFile?.mkdirs()
            targetFile.writeText("")
        }
    }
}
