package org.maiwithu.maidroid.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureCodesTest {
    @Test
    fun codesAreShortUniqueAndUppercase() {
        val codes = FailureStep.entries.map { it.code } + FailureCause.entries.map { it.code }
        assertEquals("错误码不能重复", codes.size, codes.toSet().size)
        codes.forEach { code ->
            assertTrue(code, code.matches(Regex("[A-Z0-9]+(-[A-Z0-9]+)+")))
            assertTrue(code, code.length <= 12)
        }
    }

    @Test
    fun codeCombinesStepAndRecognizedCause() {
        assertEquals("UV-SYNC", Failure(FailureStep.UvSync, "安装 Python 依赖包失败").code)
        val failure = failureOf(
            FailureStep.MaiBotClone,
            "git clone 失败",
            output = "fatal: unable to access 'https://ghproxy.vip/https://github.com/Mai-with-u/MaiBot.git/': " +
                "Could not resolve host: ghproxy.vip"
        )
        assertEquals("MB-CLONE/NET-DNS", failure.code)
        assertEquals("[MaiDroid] 错误码 MB-CLONE/NET-DNS git clone 失败", failure.logLine())
    }

    @Test
    fun failureLogLineIsParsedBackFromLogFile() {
        val line = "11-01 12:30:00.250 [MaiDroid] 错误码 UV-SYNC/NET-DNS 安装 Python 依赖包失败：dns error"
        assertEquals("UV-SYNC/NET-DNS" to "安装 Python 依赖包失败：dns error", parseFailureLogLine(line))
        assertNull(parseFailureLogLine("[MaiBot] 普通日志"))
        assertNull(parseFailureLogLine("[MaiDroid] 错误码 不是码 说明"))
    }

    @Test
    fun aptOutputsAreRecognized() {
        assertEquals(
            FailureCause.Dns,
            diagnoseFailureCause(
                "Err:1 http://mirrors.tuna.tsinghua.edu.cn/debian bookworm InRelease\n" +
                    "  Temporary failure resolving 'mirrors.tuna.tsinghua.edu.cn'"
            )
        )
        assertEquals(
            FailureCause.ClockSkew,
            diagnoseFailureCause(
                "E: Release file for http://mirrors.tuna.tsinghua.edu.cn/debian/dists/bookworm-updates/InRelease " +
                    "is not valid yet (invalid for another 1d 2h 3min 4s). Updates for this repository will not be applied."
            )
        )
        assertEquals(
            FailureCause.AptMirrorSyncing,
            diagnoseFailureCause("E: Failed to fetch http://mirrors.tuna.tsinghua.edu.cn/debian/x.deb  Hash Sum mismatch")
        )
        assertEquals(
            FailureCause.AptLocked,
            diagnoseFailureCause("E: Could not get lock /var/lib/dpkg/lock-frontend. It is held by process 123 (apt-get)")
        )
    }

    @Test
    fun specificCausesWinOverNetworkNoise() {
        // 前几个镜像解析失败，最后一次写盘时空间满：报空间不足，不报网络
        val output = "Could not resolve host: ghproxy.vip\n" +
            "error: failed to write /root/.cache/uv/archive: No space left on device (os error 28)"
        assertEquals(FailureCause.DiskFull, diagnoseFailureCause(output))
    }

    @Test
    fun uvOutputsAreRecognized() {
        assertEquals(
            FailureCause.Timeout,
            diagnoseFailureCause(
                "error: Failed to fetch: `https://mirrors.aliyun.com/pypi/simple/aiohttp/`\n" +
                    "  Caused by: Request failed after 3 retries\n" +
                    "  Caused by: error sending request for url (https://mirrors.aliyun.com/pypi/simple/aiohttp/)\n" +
                    "  Caused by: operation timed out"
            )
        )
        assertEquals(
            FailureCause.DependencyConflict,
            diagnoseFailureCause("  × No solution found when resolving dependencies:\n  ╰─▶ Because foo was not found")
        )
    }

    @Test
    fun maiBotRuntimeCausesIgnoreHarmlessNetworkErrors() {
        val output = "[LLM] 请求超时：operation timed out\n" +
            "ModuleNotFoundError: No module named 'aiohttp'"
        assertEquals(FailureCause.MissingModule, diagnoseFailureCause(output, among = MAIBOT_RUNTIME_CAUSES))
        assertNull(diagnoseFailureCause("[LLM] 请求超时：operation timed out", among = MAIBOT_RUNTIME_CAUSES))
        assertEquals(
            FailureCause.DatabaseDowngrade,
            diagnoseFailureCause(
                "DatabaseMigrationExecutionError: 当前数据库版本高于代码内注册的最新迁移版本，已拒绝继续启动。",
                among = MAIBOT_RUNTIME_CAUSES
            )
        )
    }

    @Test
    fun killedOnlyMatchesWholeLine() {
        assertEquals(FailureCause.OutOfMemory, diagnoseFailureCause("building...\nKilled\n"))
        assertNull(diagnoseFailureCause("[NapCat] process Killed by user request, restarting"))
    }

    @Test
    fun onlyTheTailOfLongOutputCounts() {
        val output = "Could not resolve host: old.example\n" + "ok\n".repeat(500)
        assertNull(diagnoseFailureCause(output))
        assertNull(diagnoseFailureCause(""))
    }

    @Test
    fun okHttpExceptionsAreRecognized() {
        // 应用自更新下载失败时记的是“异常类名: 说明”
        assertEquals(
            FailureCause.Dns,
            diagnoseFailureCause("UnknownHostException: Unable to resolve host \"github.com\": No address associated with hostname")
        )
        assertEquals(FailureCause.Timeout, diagnoseFailureCause("SocketTimeoutException: timeout"))
        assertEquals(
            FailureCause.Tls,
            diagnoseFailureCause("SSLHandshakeException: java.security.cert.CertPathValidatorException: Trust anchor not found")
        )
        assertEquals(
            FailureCause.ClockSkew,
            diagnoseFailureCause("SSLHandshakeException: CertificateNotYetValidException: Certificate not valid until 2027")
        )
        assertEquals(FailureCause.ConnectionLost, diagnoseFailureCause("ConnectException: Failed to connect to /1.2.3.4:443"))
    }
}
