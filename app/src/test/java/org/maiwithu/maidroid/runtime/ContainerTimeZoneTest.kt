package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.SimpleTimeZone
import java.util.TimeZone

class ContainerTimeZoneTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val hour = 3_600_000
    private val july2026 = 1_782_864_000_000L
    private val january2026 = 1_767_225_600_000L

    // 容器 tzdata 里只有 Etc/ 下的固定偏移时区，用来模拟手机时区在容器里找不到同名文件
    private val onlyFixedOffsets: (String) -> Boolean = { it.startsWith("Etc/") }

    @Test
    fun `phone zone known to the container is used as is`() {
        val zoneId = ContainerTimeZone.containerZoneId(TimeZone.getTimeZone("Asia/Hong_Kong"), july2026) { true }

        assertEquals("Asia/Hong_Kong", zoneId)
    }

    @Test
    fun `unknown zone falls back to a fixed offset with the inverted Etc sign`() {
        assertEquals(
            "Etc/GMT-8",
            ContainerTimeZone.containerZoneId(SimpleTimeZone(8 * hour, "Asia/Brand_New"), july2026, onlyFixedOffsets)
        )
        assertEquals(
            "Etc/GMT+5",
            ContainerTimeZone.containerZoneId(SimpleTimeZone(-5 * hour, "GMT-05:00"), july2026, onlyFixedOffsets)
        )
    }

    @Test
    fun `fixed offset fallback follows daylight saving at the given moment`() {
        val newYork = TimeZone.getTimeZone("America/New_York")

        assertEquals("Etc/GMT+4", ContainerTimeZone.containerZoneId(newYork, july2026, onlyFixedOffsets))
        assertEquals("Etc/GMT+5", ContainerTimeZone.containerZoneId(newYork, january2026, onlyFixedOffsets))
    }

    @Test
    fun `half hour offsets and UTC fall back to Etc UTC`() {
        assertEquals(
            "Etc/UTC",
            ContainerTimeZone.containerZoneId(SimpleTimeZone(5 * hour + hour / 2, "GMT+05:30"), july2026, onlyFixedOffsets)
        )
        assertEquals("Etc/UTC", ContainerTimeZone.containerZoneId(SimpleTimeZone(0, "GMT"), july2026, onlyFixedOffsets))
    }

    @Test
    fun `zone ids that could escape the zoneinfo folder are never used as paths`() {
        val zoneId = ContainerTimeZone.containerZoneId(SimpleTimeZone(9 * hour, "../../etc/passwd"), july2026) { true }

        assertEquals("Etc/GMT-9", zoneId)
    }

    @Test
    fun `rootfs without zoneinfo is left untouched`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()

        assertNull(ContainerTimeZone.sync(rootfs, TimeZone.getTimeZone("Asia/Hong_Kong")))
        assertFalse(File(rootfs, "etc/timezone").exists())
    }

    @Test
    fun `sync points localtime at the phone zone and rewrites etc timezone`() {
        val rootfs = tmp.newFolder("rootfs")
        File(rootfs, "usr/share/zoneinfo/Asia").mkdirs()
        File(rootfs, "usr/share/zoneinfo/Asia/Hong_Kong").writeText("TZif")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/timezone").writeText("Etc/UTC\n")
        val localtime = File(rootfs, "etc/localtime").toPath()
        // Windows 没开开发者模式时不能建软链接，这时跳过这条
        assumeTrue(runCatching { Files.createSymbolicLink(localtime, Paths.get("/usr/share/zoneinfo/Etc/UTC")) }.isSuccess)

        val zoneId = ContainerTimeZone.sync(rootfs, TimeZone.getTimeZone("Asia/Hong_Kong"))

        assertEquals("Asia/Hong_Kong", zoneId)
        assertEquals(Paths.get("/usr/share/zoneinfo/Asia/Hong_Kong"), Files.readSymbolicLink(localtime))
        assertEquals("Asia/Hong_Kong\n", File(rootfs, "etc/timezone").readText())
        assertFalse(File(rootfs, "etc/.localtime.maidroid").exists())
    }
}
