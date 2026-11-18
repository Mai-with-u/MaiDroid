package org.maiwithu.maidroid.remote

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.diagnostics.BundleEntry
import org.maiwithu.maidroid.diagnostics.DiagnosticSnapshotMeta
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureCause
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.FeedbackPackage
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

class DiagnosticSnapshotPayloadTest {
    private val lenientJson = Json { ignoreUnknownKeys = true }

    @Test
    fun trimsOversizedLogsToStayUnder128KiBGzipLimitAndPreservesTail() {
        // 构造伪随机难压缩日志行以逼迫触发裁剪档位，确认最末尾的新报错行一定保留且 Gzip 体积 ≤ 128 KB
        val noisyLines = buildString {
            var seed = 0x12345678L
            repeat(8_000) { idx ->
                seed = seed * 6364136223846793005L + 1442695040888963407L
                appendLine("line-$idx-${seed.toULong().toString(36)}-${(seed xor (seed ushr 17)).toULong().toString(36)}")
            }
            appendLine("FINAL-CRITICAL-LINE: DNS lookup failed")
        }

        val pkg = FeedbackPackage(
            failure = Failure(FailureStep.UvSync, "依赖同步失败", FailureCause.Dns),
            reportText = "MaiDroid 诊断报告\n错误码：UV-SYNC/NET-DNS",
            clipboardText = "",
            issueUrl = "",
            entries = listOf(
                BundleEntry("terminal/terminal-1.log", noisyLines.toByteArray()),
                BundleEntry("maibot/app_latest.log", noisyLines.toByteArray())
            ),
            fileName = "diag.zip",
            terminalLogCount = 1,
            crashCount = 0,
            includesMaiBotLog = true,
            snapshotMeta = DiagnosticSnapshotMeta(
                appVersion = "0.2.4",
                versionCode = 9,
                buildType = "debug",
                device = "HONOR OXF-AN10",
                manufacturer = "HONOR",
                sdkInt = 31,
                availableStorageMb = 2048,
                availableMemMb = 3100,
                storageGranted = true,
                notificationsEnabled = true,
                batteryIgnored = false,
                lastExitReason = "闪退"
            )
        )

        val gzipBytes = DiagnosticSnapshotPayload.buildGzipBytes(pkg)
        assertTrue("Gzip size ${gzipBytes.size} exceeds 128 KiB", gzipBytes.size <= DiagnosticSnapshotPayload.MAX_GZIP_BYTES)

        val jsonText = GZIPInputStream(ByteArrayInputStream(gzipBytes)).bufferedReader().use { it.readText() }
        val doc = lenientJson.decodeFromString<DiagnosticSnapshotPayload.SnapshotDocument>(jsonText)
        assertEquals("UV-SYNC/NET-DNS", doc.errorCode)
        assertEquals(false, doc.permissions?.batteryIgnored)
        assertTrue(doc.terminalTail.contains("FINAL-CRITICAL-LINE: DNS lookup failed"))
    }
}
