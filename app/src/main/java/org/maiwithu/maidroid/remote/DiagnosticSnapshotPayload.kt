package org.maiwithu.maidroid.remote

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maiwithu.maidroid.diagnostics.FeedbackPackage
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * 把已经由 DiagnosticsManager 打过码的 FeedbackPackage 打包并 Gzip 压缩成 ≤ 128 KB 的上传字节流。
 * 当终端日志或 MaiBot 日志很长时，按步进缩减日志尾部字符数重新压缩，保证诊断报告正文和最新报错栈完整送达。
 */
internal object DiagnosticSnapshotPayload {

    const val MAX_GZIP_BYTES = 128 * 1024

    @Serializable
    internal data class SnapshotPermissions(
        val storage: Boolean,
        val notifications: Boolean,
        val batteryIgnored: Boolean
    )

    @Serializable
    internal data class SnapshotDocument(
        val schemaVersion: Int = 1,
        val appVersion: String,
        val versionCode: Int,
        val buildType: String,
        val device: String,
        val manufacturer: String,
        val sdkInt: Int,
        val availableStorageMb: Long? = null,
        val availableMemMb: Long? = null,
        val permissions: SnapshotPermissions? = null,
        val errorCode: String? = null,
        val failureMessage: String? = null,
        val lastExitReason: String? = null,
        val reportText: String,
        val terminalTail: String,
        val maiBotLogTail: String,
        val latestCrash: String
    )

    private val snapshotJson = Json { encodeDefaults = true }

    // 从宽到窄依次尝试保留的日志尾部字符上限；Gzip 对文本通常有 4~8 倍压缩率，首档 180K 字符一般就能压进 40KB 内。
    private val TAIL_CHAR_BUDGETS = intArrayOf(180_000, 90_000, 45_000, 20_000, 8_000, 0)

    fun buildGzipBytes(pkg: FeedbackPackage): ByteArray {
        val terminalFull = pkg.entries
            .filter { it.name.startsWith("terminal/") }
            .joinToString("\n\n") { "=== ${it.name} ===\n" + it.content.toString(Charsets.UTF_8) }
        val maiBotFull = pkg.entries
            .firstOrNull { it.name.startsWith("maibot/") }
            ?.content?.toString(Charsets.UTF_8)
            .orEmpty()
        val crashFull = pkg.entries
            .firstOrNull { it.name.startsWith("crashes/") }
            ?.content?.toString(Charsets.UTF_8)
            .orEmpty()

        val meta = pkg.snapshotMeta
        val errorCode = pkg.failure?.code

        for (budget in TAIL_CHAR_BUDGETS) {
            val doc = SnapshotDocument(
                appVersion = meta?.appVersion ?: "unknown",
                versionCode = meta?.versionCode ?: 0,
                buildType = meta?.buildType ?: "unknown",
                device = meta?.device ?: "unknown",
                manufacturer = meta?.manufacturer ?: "unknown",
                sdkInt = meta?.sdkInt ?: 0,
                availableStorageMb = meta?.availableStorageMb,
                availableMemMb = meta?.availableMemMb,
                permissions = meta?.let {
                    SnapshotPermissions(
                        storage = it.storageGranted,
                        notifications = it.notificationsEnabled,
                        batteryIgnored = it.batteryIgnored
                    )
                },
                errorCode = errorCode,
                failureMessage = pkg.failure?.message,
                lastExitReason = meta?.lastExitReason,
                reportText = pkg.reportText.take(32_000),
                terminalTail = takeTailLinesWithinChars(terminalFull, budget),
                maiBotLogTail = takeTailLinesWithinChars(maiBotFull, budget / 2),
                latestCrash = crashFull.take(24_000)
            )
            val compressed = gzipUtf8(snapshotJson.encodeToString(doc))
            if (compressed.size <= MAX_GZIP_BYTES) return compressed
        }

        // 兜底只留诊断报告本身，确保任何极端情况下都能生成求助码
        val minimal = SnapshotDocument(
            appVersion = meta?.appVersion ?: "unknown",
            versionCode = meta?.versionCode ?: 0,
            buildType = meta?.buildType ?: "unknown",
            device = meta?.device ?: "unknown",
            manufacturer = meta?.manufacturer ?: "unknown",
            sdkInt = meta?.sdkInt ?: 0,
            errorCode = errorCode,
            failureMessage = pkg.failure?.message,
            reportText = pkg.reportText.take(24_000),
            terminalTail = "",
            maiBotLogTail = "",
            latestCrash = ""
        )
        return gzipUtf8(snapshotJson.encodeToString(minimal))
    }

    private fun takeTailLinesWithinChars(text: String, maxChars: Int): String {
        if (maxChars <= 0 || text.isEmpty()) return ""
        if (text.length <= maxChars) return text
        val slice = text.takeLast(maxChars)
        val firstNewline = slice.indexOf('\n')
        return if (firstNewline in 0 until slice.lastIndex) slice.substring(firstNewline + 1) else slice
    }

    private fun gzipUtf8(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gzip ->
            gzip.write(text.toByteArray(Charsets.UTF_8))
        }
        return bos.toByteArray()
    }
}
