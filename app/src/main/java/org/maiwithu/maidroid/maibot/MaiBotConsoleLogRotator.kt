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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MaiBot 控制台日志（worker-console.log）磁盘兜底轮转器：MaiBot 自己会给 app_*.log.jsonl 滚卷（~5MB 一卷），
 * 但 worker-console.log 不滚动：
 * - 旧版路径 `logs/worker-console.log` 由旧 Runner 以 O_APPEND 写入，从宿主侧 copytruncate（复制留副本 ->
 *   把正文件截成 0）在运行期也是安全的：截断后写入方下一个 append 会从新的文件尾继续。
 * - 1.4.x 多实例宿主改为写各实例 `temp/worker-console.log`，且宿主 `src/host/process.py` 靠内存 `offset`
 *   增量读它转发到 stdout；如果运行中途把文件截为 0，宿主会在文件重新长到 `offset` 之前停止转发后续日志。
 *   因此定时巡检只管旧版 `logs/worker-console.log`，1.4.x 的 `temp/worker-console.log` 改为只在 MaiBot
 *   停机后、拉起前由 [rotateWorkerConsoleLogsWhenStopped] 共用同一套 copytruncate 轮转逻辑处理。
 *
 * （不改第三方源码：这里只是对第三方生成的文件做磁盘管理，不改 MaiBot 的任何行为与格式。）
 */
internal class MaiBotConsoleLogRotator(private val paths: RuntimePaths) {

    companion object {
        private const val TAG = "MaiBotConsoleLogRotator"
        internal const val LOG_PREFIX = "[MaiBot]"

        /** 每隔多久看一眼大小。 */
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

        /** 超过 8MB 才滚动：正常 ~0.8MB/天，约十天才滚一次，副本 + 正文件合计 ≤16MB 封顶。 */
        internal const val ROTATE_SIZE_BYTES = 8L * 1024L * 1024L
    }

    private val scope = backgroundScope("MaiBot 控制台日志轮转", Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** 幂等启动（AppDependencies 统一调用）：首次启动立即先检查一次，随后按固定周期巡检，安全退出时随进程结束。 */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            while (isActive) {
                runCatching { rotateIfNeeded() }.onFailure { e ->
                    Log.w(TAG, "worker-console.log rotation failed", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    // 运行期定期巡检只处理旧版 logs/worker-console.log，避免打断 1.4.x 宿主对 temp/worker-console.log 的偏移转发。
    private fun rotateIfNeeded() {
        rotateConsoleFileIfNeeded(
            console = File(paths.maiBotDir, "logs/worker-console.log"),
            label = "worker-console.log"
        )
    }
}

/**
 * 在 MaiBot 完全停止期间、下一次启动之前检查各实例的 `temp/worker-console.log`（含根目录），
 * 超限时复用同一套安全 copytruncate 规则轮转到同目录的 `worker-console.log.1`。
 */
internal fun rotateWorkerConsoleLogsWhenStopped(
    paths: RuntimePaths,
    rotateSizeBytes: Long = MaiBotConsoleLogRotator.ROTATE_SIZE_BYTES
) {
    val candidates = (MaiBotInstances.discover(paths).map { it.workerConsoleLog } +
        File(paths.maiBotDir, "temp/worker-console.log"))
        .distinctBy { it.absolutePath }
    for (console in candidates) {
        val label = runCatching { console.relativeTo(paths.maiBotDir).invariantSeparatorsPath }
            .getOrElse { console.name }
        rotateConsoleFileIfNeeded(console, label, rotateSizeBytes)
    }
}

/**
 * 超过阈值就对单个控制台日志文件做 copytruncate：
 * 副本先写临时文件再用原子替换放上 -> 正文件截成 0，并记一行终端日志，失败可安全重试。
 */
internal fun rotateConsoleFileIfNeeded(
    console: File,
    label: String,
    rotateSizeBytes: Long = MaiBotConsoleLogRotator.ROTATE_SIZE_BYTES,
    onWarning: (String, Throwable) -> Unit = { msg, err -> Log.w("MaiBotConsoleLogRotator", msg, err) }
) {
    if (!console.isFile) return
    val sizeBytes = console.length()
    if (sizeBytes < rotateSizeBytes) return
    val parentDir = console.parentFile ?: return
    val backup = File(parentDir, "${console.name}.1")
    val tmp = File(parentDir, "${console.name}.1.tmp")
    console.copyTo(tmp, overwrite = true)
    val moved = runCatching {
        Files.move(tmp.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrElse { error ->
        onWarning("${console.name} backup move failed, keeping original", error)
        tmp.delete()
        false
    }
    if (!moved) return
    RandomAccessFile(console, "rw").use { it.setLength(0) }
    TerminalLog.append(
        "${MaiBotConsoleLogRotator.LOG_PREFIX} $label reached ${sizeBytes / 1024 / 1024}MB, rotated to ${backup.name}"
    )
}
