package org.maiwithu.maidroid.maibot

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.terminal.TerminalLog
import java.io.File
import java.io.RandomAccessFile

/**
 * MaiBot 日志盘上体积的兜底管家：MaiBot 自己会给 app_*.log.jsonl 滚卷（~5MB 一卷），
 * 但 worker-console.log 不滚动、且是 O_APPEND 写入的（2026-11-09 实测 fd flags 0402001），
 * 从宿主侧 copytruncate（复制留副本 -> 把正文件截成 0）对这种文件是安全的：
 * 截断后写入方下一个 append 会从新的文件尾继续，不会留出空洞也不丢之后的输出。
 *
 * 宿主侧直接动 MaiDroid 自己的应用私有目录，不执行容器命令、不依赖任何后台管理豁免；
 * 只保留一份日志副本，总占用上限 = 两份 × ROTATE_SIZE_BYTES。
 *
 * （不改第三方源码：这里只是对第三方生成的文件做磁盘管理，不改 MaiBot 的任何行为与格式。）
 */
internal class MaiBotLogHousekeeper(private val paths: RuntimePaths) {

    companion object {
        private const val TAG = "MaiBotLogHousekeeper"
        private const val LOG_PREFIX = "[MaiBot]"

        /** 每隔多久看一眼大小。 */
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

        /** 超过 8MB 才滚动：正常 ~0.8MB/天，约十天才滚一次，副本 + 正文件合计 ≤16MB 封顶。 */
        private const val ROTATE_SIZE_BYTES = 8L * 1024L * 1024L
    }

    private val scope = backgroundScope("MaiBot 日志管家", Dispatchers.IO)

    /** 幂等启动（AppDependencies 统一调用）；随应用进程存活，安全退出时进程本身结束，不需要单独停。 */
    fun start() {
        scope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                runCatching { rotateIfNeeded() }.onFailure { e ->
                    Log.w(TAG, "worker-console.log rotation failed", e)
                }
            }
        }
    }

    /** 超过阈值就 copytruncate：副本先写临时文件再改名（原子换上），正文件截 0，失败可安全重试。 */
    private fun rotateIfNeeded() {
        val logsDir = File(paths.maiBotDir, "logs")
        val console = File(logsDir, "worker-console.log")
        if (!console.isFile) return
        val sizeBytes = console.length()
        if (sizeBytes < ROTATE_SIZE_BYTES) return
        val backup = File(logsDir, "worker-console.log.1")
        val tmp = File(logsDir, "worker-console.log.1.tmp")
        console.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(backup)) {
            Log.w(TAG, "worker-console.log backup rename failed, keeping original")
            tmp.delete()
            return
        }
        RandomAccessFile(console, "rw").use { it.setLength(0) }
        TerminalLog.append(
            "$LOG_PREFIX worker-console.log reached ${sizeBytes / 1024 / 1024}MB, rotated to worker-console.log.1"
        )
    }
}
