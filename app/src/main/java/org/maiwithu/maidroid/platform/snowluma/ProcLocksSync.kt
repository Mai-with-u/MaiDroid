package org.maiwithu.maidroid.platform.snowluma

import android.os.Process as AndroidProcess
import org.maiwithu.maidroid.runtime.listOwnProcessDirs
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 宿主侧维护容器 /proc/locks 替身文件：
 * Android 不允许应用进程读系统级 /proc/locks，而 SnowLuma 要靠它识别 QQ 锁着哪个账号的 nt_msg.db。
 * 宿主进程与容器同属一个应用 UID，可直接遍历 /proc/<qq_pid>/fdinfo 里的 lock: 记录并原子写入替身文件，
 * 省掉在容器内每 2 秒起一轮 bash/pgrep/grep/sed 子进程带来的 4 个幻影进程脉冲与审计刷屏。
 */
internal object ProcLocksSync {

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

    /** 把采集到的文件锁快照原子写入 targetFile：写同目录临时文件 -> 改权限 -> 覆盖正式文件。 */
    fun writeProcLocksSnapshot(
        targetFile: File,
        procRoot: File = File("/proc"),
        myUid: Int = AndroidProcess.myUid(),
        myPid: Int = AndroidProcess.myPid()
    ) {
        val lines = collectQqLockLines(procRoot, myUid, myPid)
        val content = if (lines.isEmpty()) "" else lines.joinToString("\n", postfix = "\n")
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
