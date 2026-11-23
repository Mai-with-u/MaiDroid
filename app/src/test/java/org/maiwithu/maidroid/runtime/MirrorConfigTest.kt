package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorConfigTest {
    @Test
    fun `validateAndNormalizeMirrorUrl validates scheme host shell-safe chars and category rules`() {
        val validGh = validateAndNormalizeMirrorUrl(MirrorCategory.GitHub, "HTTPS://GHFast.Top/")
        assertEquals(MirrorUrlValidationResult.Valid("https://ghfast.top"), validGh)

        // APT 会自动剥离误粘贴的 /debian 或 /debian-security 尾路径
        val validApt = validateAndNormalizeMirrorUrl(
            MirrorCategory.Apt,
            "http://MIRRORS.EXAMPLE.COM:8080/ubuntu-debian/debian/"
        )
        assertEquals(MirrorUrlValidationResult.Valid("http://mirrors.example.com:8080/ubuntu-debian"), validApt)

        val validPyPi = validateAndNormalizeMirrorUrl(MirrorCategory.PyPi, "https://PyPi.Example.com/simple/")
        assertEquals(MirrorUrlValidationResult.Valid("https://pypi.example.com/simple"), validPyPi)

        // 直接填写 github.com 时提示改选内置“直连”预设
        assertTrue(validateAndNormalizeMirrorUrl(MirrorCategory.GitHub, "https://github.com/") is MirrorUrlValidationResult.Invalid)
        // 拦截缺失协议头、查询串及 shell 元字符
        assertTrue(validateAndNormalizeMirrorUrl(MirrorCategory.PyPi, "pypi.tuna.tsinghua.edu.cn") is MirrorUrlValidationResult.Invalid)
        assertTrue(validateAndNormalizeMirrorUrl(MirrorCategory.GitHub, "https://a.com?x=1") is MirrorUrlValidationResult.Invalid)
        assertTrue(validateAndNormalizeMirrorUrl(MirrorCategory.Apt, "http://a.com;rm -rf /") is MirrorUrlValidationResult.Invalid)
    }

    @Test
    fun `order mirrors ranks reachable fastest first in Auto mode and pins chosen or custom in manual modes`() {
        val probes = mapOf(
            "http://mirrors.tuna.tsinghua.edu.cn" to MirrorProbeResult("http://mirrors.tuna.tsinghua.edu.cn", MirrorProbeStatus.Reachable, latencyMs = 240L),
            "http://mirrors.ustc.edu.cn" to MirrorProbeResult("http://mirrors.ustc.edu.cn", MirrorProbeStatus.Failed, errorReason = "超时"),
            "http://mirrors.aliyun.com" to MirrorProbeResult("http://mirrors.aliyun.com", MirrorProbeStatus.Reachable, latencyMs = 65L)
        )

        val autoOrder = orderAptMirrors(MirrorPreference(MirrorSelectionMode.Auto), probes)
        assertEquals(
            listOf(
                "http://mirrors.aliyun.com",
                "http://mirrors.tuna.tsinghua.edu.cn",
                "http://deb.debian.org",
                "http://mirrors.ustc.edu.cn"
            ),
            autoOrder.map { it.baseUrl }
        )

        val presetOrder = orderAptMirrors(
            MirrorPreference(MirrorSelectionMode.Preset, presetValue = "http://mirrors.ustc.edu.cn"),
            probes
        )
        assertEquals("http://mirrors.ustc.edu.cn", presetOrder.first().baseUrl)
        assertEquals("http://mirrors.aliyun.com", presetOrder[1].baseUrl)

        val customOrder = orderGitHubMirrors(
            MirrorPreference(MirrorSelectionMode.Custom, customUrl = "https://custom.gh.proxy/"),
            emptyMap()
        )
        assertEquals("https://custom.gh.proxy", customOrder.first().prefix)
        assertEquals("自定义镜像", customOrder.first().name)
        assertTrue(customOrder.size == GitHubMirrors.ALL.size + 1)

        val pyPiCustom = orderPyPiMirrors(
            MirrorPreference(MirrorSelectionMode.Custom, customUrl = "https://pypi.tuna.tsinghua.edu.cn/simple/"),
            emptyMap()
        )
        assertEquals("https://pypi.tuna.tsinghua.edu.cn/simple", pyPiCustom.first().indexUrl)
        // 自定义地址与预设地址相同时不产生重复条目
        assertEquals(1, pyPiCustom.count { it.indexUrl == "https://pypi.tuna.tsinghua.edu.cn/simple" })
    }

    @Test
    fun `probe validators accept genuine upstream responses and reject html error pages`() {
        assertTrue(isValidGitHubProbeResponse(200, "application/x-git-upload-pack-advertisement", "001e# service=git-upload-pack"))
        assertFalse(isValidGitHubProbeResponse(200, "text/html; charset=utf-8", "<html>blocked</html>"))
        assertFalse(isValidGitHubProbeResponse(403, "application/json", "forbidden"))

        assertTrue(isValidAptProbeResponse(200, "Origin: Debian\nLabel: Debian\nCodename: bookworm"))
        assertTrue(isValidAptProbeResponse(206, "Suite: stable\nCodename: bookworm"))
        assertFalse(isValidAptProbeResponse(200, "<html>404 not found</html>"))

        assertTrue(isValidPyPiProbeResponse(200, "<!DOCTYPE html><html><a href='pip-24.0.tar.gz'>pip</a>"))
        assertTrue(isValidPyPiProbeResponse(200, "{\"name\":\"pip\",\"files\":[]}"))
        assertFalse(isValidPyPiProbeResponse(404, "Not Found"))
        assertFalse(isValidPyPiProbeResponse(200, "<title>404 Not Found</title>"))
    }
}
