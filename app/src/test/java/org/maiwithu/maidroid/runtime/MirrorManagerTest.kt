package org.maiwithu.maidroid.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MirrorManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeMirrorPreferenceStore : MirrorPreferenceStore {
        private val map = MirrorCategory.entries.associateWith { MirrorPreference() }.toMutableMap()
        override fun mirrorPreference(category: MirrorCategory): MirrorPreference = map.getValue(category)
        override fun saveMirrorPreference(category: MirrorCategory, preference: MirrorPreference) {
            map[category] = preference
        }
    }

    @Test
    fun `auto resolve runs speed test once within TTL and skips when manual mode selected`() = runBlocking {
        val store = FakeMirrorPreferenceStore()
        val rootfs = tempFolder.newFolder("debian")
        var probeCalls = 0
        var fakeTime = 1_000_000L

        val manager = MirrorManager(
            store = store,
            rootfsDir = rootfs,
            probeCategory = { category, _, _ ->
                probeCalls++
                when (category) {
                    MirrorCategory.Apt -> mapOf(
                        "http://mirrors.ustc.edu.cn" to MirrorProbeResult("http://mirrors.ustc.edu.cn", MirrorProbeStatus.Reachable, 42L),
                        "http://mirrors.tuna.tsinghua.edu.cn" to MirrorProbeResult("http://mirrors.tuna.tsinghua.edu.cn", MirrorProbeStatus.Reachable, 180L)
                    )
                    MirrorCategory.GitHub -> mapOf(
                        "https://ghfast.top" to MirrorProbeResult("https://ghfast.top", MirrorProbeStatus.Reachable, 55L),
                        "" to MirrorProbeResult("", MirrorProbeStatus.Failed, errorReason = "连接超时")
                    )
                    MirrorCategory.PyPi -> mapOf(
                        "https://mirrors.cloud.tencent.com/pypi/simple" to MirrorProbeResult("https://mirrors.cloud.tencent.com/pypi/simple", MirrorProbeStatus.Reachable, 38L)
                    )
                }
            },
            nowEpochMs = { fakeTime },
            logLine = {}
        )

        val aptFirst = manager.resolveAptMirrors(allowSpeedTest = true)
        assertEquals("http://mirrors.ustc.edu.cn", aptFirst.first().baseUrl)
        assertEquals(1, probeCalls)

        // 10 分钟 TTL 内复用测速缓存，不再重复发起网络探测
        fakeTime += 3 * 60_000L
        val aptSecond = manager.resolveAptMirrors(allowSpeedTest = true)
        assertEquals("http://mirrors.ustc.edu.cn", aptSecond.first().baseUrl)
        assertEquals(1, probeCalls)

        // 超过 10 分钟 TTL 后再次 resolve 自动重测
        fakeTime += 8 * 60_000L
        manager.resolveAptMirrors(allowSpeedTest = true)
        assertEquals(2, probeCalls)

        // 切为手动指定预设后不再触发自动测速，且首项切换为指定源
        manager.selectPreset(MirrorCategory.Apt, "http://mirrors.aliyun.com")
        val aptManual = manager.resolveAptMirrors(allowSpeedTest = true)
        assertEquals("http://mirrors.aliyun.com", aptManual.first().baseUrl)
        assertTrue(manager.isManualSelection(MirrorCategory.Apt))
        assertEquals(2, probeCalls)
    }

    @Test
    fun `container mirror files are synced atomically only when unpacked rootfs directories exist`() = runBlocking {
        val store = FakeMirrorPreferenceStore()
        val emptyRootfs = tempFolder.newFolder("empty-rootfs")
        val manager = MirrorManager(
            store = store,
            rootfsDir = emptyRootfs,
            probeCategory = { _, _, _ -> emptyMap() },
            logLine = {}
        )

        // rootfs 未解包时不得创建伪造的 etc/apt 或 root/.maidroid 目录
        manager.saveCustomUrl(MirrorCategory.Apt, "http://mirror.local/ubuntu/debian/")
        manager.syncContainerMirrorFile(MirrorCategory.Apt)
        manager.syncContainerMirrorFile(MirrorCategory.PyPi)
        assertFalse(File(emptyRootfs, "etc").exists())
        assertFalse(File(emptyRootfs, "root").exists())

        // 模拟已解包容器目录存在 etc/apt 与 root/
        File(emptyRootfs, "etc/apt").mkdirs()
        File(emptyRootfs, "etc/os-release").writeText("ID=debian\nVERSION_CODENAME=trixie\n")
        File(emptyRootfs, "root").mkdirs()

        manager.resolveAptMirrors(allowSpeedTest = false)
        val sourcesText = File(emptyRootfs, "etc/apt/sources.list").readText()
        assertTrue(sourcesText.contains("deb http://mirror.local/ubuntu/debian trixie main contrib"))
        assertTrue(sourcesText.contains("deb http://mirror.local/ubuntu/debian-security trixie-security main contrib"))

        assertNull(manager.saveCustomUrl(MirrorCategory.PyPi, "https://custom.pypi.local/simple/"))
        manager.resolvePyPiMirrors(allowSpeedTest = false)
        val pypiFile = File(emptyRootfs, "root/.maidroid/pypi-index")
        assertTrue(pypiFile.isFile)
        assertEquals("https://custom.pypi.local/simple\n", pypiFile.readText())

        // 选回“MaiBot 自带的源”时删除 pypi-index 覆盖文件
        manager.selectPreset(MirrorCategory.PyPi, "")
        manager.syncContainerMirrorFile(MirrorCategory.PyPi)
        assertFalse(pypiFile.exists())
        assertNotNull(manager.saveCustomUrl(MirrorCategory.GitHub, "https://github.com"))
    }
}
