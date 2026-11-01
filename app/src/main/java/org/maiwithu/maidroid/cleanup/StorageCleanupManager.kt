package org.maiwithu.maidroid.cleanup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.appupdate.formatBytes
import org.maiwithu.maidroid.runtime.ContainerCommandRunner
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.RuntimeReadiness
import org.maiwithu.maidroid.terminal.TerminalLog

/** 量出来的结果：每类占多少字节，以及手机剩余空间。 */
internal data class CleanupSizes(
    val sizes: Map<CleanupItem, Long>,
    val freeBytes: Long
)

/** 清理完的结果：手机多出来的空间、因为正在装依赖或装软件而跳过的几类、删完后再量的大小。 */
internal data class CleanupOutcome(
    val freedBytes: Long,
    val skipped: Set<CleanupItem>,
    val sizesAfter: Map<CleanupItem, Long>,
    val success: Boolean
)

/**
 * 设置页和“空间不足”失败提示里的“清理空间”：先量 -> 用户勾选 -> 在容器里删 -> 按手机剩余空间的变化报腾出了多少。
 * 腾出的空间按删前删后的剩余空间算，不按量出来的大小，这样报的是手机上实际多出来的。
 * MaiBot、NapCat 运行中也能清：删的都是它们运行时不用的文件，正在装依赖、装软件时脚本会跳过对应的那类。
 */
internal class StorageCleanupManager(
    private val paths: RuntimePaths,
    private val readiness: RuntimeReadiness,
    private val commandRunner: ContainerCommandRunner
) {
    /** 容器还没装好（首次引导早期）时返回 null，界面改为打开系统的存储设置。 */
    suspend fun measure(): CleanupSizes? {
        if (withContext(Dispatchers.IO) { readiness.containerProblem() } != null) return null
        val result = commandRunner.run(StorageCleanupScripts.measure())
        if (!result.success) return null
        return CleanupSizes(parseCleanupSizes(result.output), freeBytes())
    }

    suspend fun clean(items: Set<CleanupItem>): CleanupOutcome {
        TerminalLog.append("")
        TerminalLog.append("$LOG_PREFIX 开始清理空间：${items.joinToString("、") { it.label }}")
        val before = freeBytes()
        // 脚本里“已清理”“跳过”这些说明写进终端日志，size、skipped 这类给程序读的行不写
        val result = commandRunner.run(StorageCleanupScripts.clean(items)) { line ->
            if (!line.startsWith("size.") && !line.startsWith("skipped=")) TerminalLog.append(line)
        }
        val freed = (freeBytes() - before).coerceAtLeast(0L)
        TerminalLog.append("$LOG_PREFIX 清理完成，手机多出约 ${formatBytes(freed)}")
        return CleanupOutcome(
            freedBytes = freed,
            skipped = parseSkippedCleanup(result.output),
            sizesAfter = parseCleanupSizes(result.output),
            success = result.success
        )
    }

    private suspend fun freeBytes(): Long = withContext(Dispatchers.IO) { paths.rootfsDir.usableSpace }

    private companion object {
        const val LOG_PREFIX = "[MaiDroid]"
    }
}
