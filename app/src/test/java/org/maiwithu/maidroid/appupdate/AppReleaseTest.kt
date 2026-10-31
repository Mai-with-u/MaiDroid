package org.maiwithu.maidroid.appupdate

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppReleaseTest {
    private val apkSha = "cd90a6fa8c71d34981ab38af5ef882c8342e2996be7afbaeaec9e1e92416f4ec"
    private val certSha = "04adb9b0b7714da34a16ecabc400633647d63de6cc805d19c38fa1ac384ee286"

    // 发布流程写出的 Release 说明：CHANGELOG 一节 + 分隔线 + 校验行
    private val releaseBody = """
        本版本修复了若干问题。

        ### 新增

        - 设置页可以检查 **MaiDroid** 更新，详见 [README](https://example.com/readme)
          - 下载走 `GitHub` 镜像

        ### 修复

        - 修好了一个问题

        ---

        校验：`MaiDroid_v0.1.3.apk` 的 SHA-256 为 `$apkSha`，签名证书 SHA-256 为 `${certSha.uppercase()}`。
    """.trimIndent()

    private fun releaseJson(
        tag: String = "v0.1.3",
        assets: String = """[{"name":"MaiDroid_v0.1.3.apk","size":163110537,
            "browser_download_url":"https://github.com/Mai-with-u/MaiDroid/releases/download/v0.1.3/MaiDroid_v0.1.3.apk"}]""",
        prerelease: Boolean = false
    ): String = """
        {"tag_name":"$tag","html_url":"https://github.com/Mai-with-u/MaiDroid/releases/tag/$tag",
         "draft":false,"prerelease":$prerelease,"published_at":"2026-10-29T01:09:39Z",
         "assets":$assets,
         "body":${JsonPrimitive(releaseBody)}}
    """.trimIndent()

    @Test
    fun `versions compare numerically and stable beats prerelease`() {
        val v012 = AppVersion.parse("0.1.2")!!
        assertTrue(AppVersion.parse("v0.1.3")!! > v012)
        assertTrue(AppVersion.parse("0.1.10")!! > AppVersion.parse("0.1.9")!!)
        assertTrue(AppVersion.parse("0.2")!! > v012)
        assertEquals(0, AppVersion.parse("0.1.2.0")!!.compareTo(v012))
        assertTrue(AppVersion.parse("0.2.0")!! > AppVersion.parse("0.2.0-rc1")!!)
        assertTrue(AppVersion.parse("0.2.0-rc2")!! > AppVersion.parse("0.2.0-rc1")!!)
        assertTrue(AppVersion.parse("0.2.0-rc.10")!! > AppVersion.parse("0.2.0-rc.9")!!)
        assertTrue(AppVersion.parse("0.2.0-rc1")!! > v012)
    }

    @Test
    fun `version parsing rejects junk and keeps the canonical text`() {
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("v1..2"))
        assertNull(AppVersion.parse("1.2.3-"))
        assertNull(AppVersion.parse("1.2.3-../x"))
        assertEquals("0.1.0-beta", AppVersion.parse("v0.1.0-beta").toString())
        assertEquals("1.0.0", AppVersion.parse("1.0.0+build7").toString())
    }

    @Test
    fun `parses latest release with apk and checksums`() {
        val release = parseAppRelease(releaseJson())!!

        assertEquals("v0.1.3", release.tag)
        assertEquals(AppVersion.parse("0.1.3"), release.version)
        assertEquals("MaiDroid_v0.1.3.apk", release.apk?.name)
        assertEquals(163110537L, release.apk?.size)
        assertEquals(apkSha, release.apkSha256)
        assertEquals(certSha, release.certSha256)
        assertEquals(1_793_236_179L, release.publishedAtEpochSeconds)
        assertTrue(release.notes.endsWith("- 修好了一个问题"))
    }

    @Test
    fun `mirror error pages and prereleases are not releases`() {
        assertNull(parseAppRelease("<html>403 Forbidden</html>"))
        assertNull(parseAppRelease("""{"message":"API rate limit exceeded"}"""))
        assertNull(parseAppRelease(releaseJson(tag = "nightly")))
        assertNull(parseAppRelease(releaseJson(prerelease = true)))
    }

    @Test
    fun `apk checksum is only trusted when the file name matches the picked asset`() {
        val otherName = """[{"name":"MaiDroid-arm64.apk","size":1,"browser_download_url":"https://x/y.apk"}]"""
        val release = parseAppRelease(releaseJson(assets = otherName))!!

        assertEquals("MaiDroid-arm64.apk", release.apk?.name)
        assertNull(release.apkSha256)
        assertEquals(certSha, release.certSha256)
    }

    @Test
    fun `release without any apk keeps the version but no asset`() {
        val release = parseAppRelease(releaseJson(assets = "[]"))

        assertNotNull(release)
        assertNull(release!!.apk)
    }

    @Test
    fun `picks the named apk first then a single apk`() {
        val named = AppReleaseAsset("MaiDroid_v0.1.3.apk", 1, "a")
        val other = AppReleaseAsset("other.apk", 1, "b")
        val notes = AppReleaseAsset("notes.txt", 1, "c")

        assertEquals(named, pickApkAsset("v0.1.3", listOf(other, named, notes)))
        assertEquals(other, pickApkAsset("v0.1.3", listOf(other, notes)))
        assertNull(pickApkAsset("v0.1.3", listOf(other, AppReleaseAsset("x.APK", 1, "d"))))
        assertNull(pickApkAsset("v0.1.3", listOf(notes)))
    }

    @Test
    fun `notes drop the separator and checksum line`() {
        val notes = releaseNotesWithoutChecksums(releaseBody.replace("\n", "\r\n"))

        assertTrue(notes.startsWith("本版本修复了若干问题。"))
        assertTrue(notes.endsWith("- 修好了一个问题"))
        assertEquals("只有正文", releaseNotesWithoutChecksums("只有正文\n"))
        assertEquals(ReleaseChecksums(), parseReleaseChecksums("只有正文"))
    }

    @Test
    fun `note lines turn markdown into headings and bullets`() {
        val lines = releaseNoteLines(releaseNotesWithoutChecksums(releaseBody))

        assertEquals(
            listOf(
                ReleaseNoteLine(ReleaseNoteKind.Text, "本版本修复了若干问题。"),
                ReleaseNoteLine(ReleaseNoteKind.Heading, "新增"),
                ReleaseNoteLine(ReleaseNoteKind.Bullet, "设置页可以检查 MaiDroid 更新，详见 README"),
                ReleaseNoteLine(ReleaseNoteKind.Bullet, "下载走 GitHub 镜像", indent = 1),
                ReleaseNoteLine(ReleaseNoteKind.Heading, "修复"),
                ReleaseNoteLine(ReleaseNoteKind.Bullet, "修好了一个问题")
            ),
            lines
        )
    }

    @Test
    fun `partial content must start where the part file ends`() {
        val total = 163110537L
        assertEquals(
            1024L,
            rangeResponseStart(206, "bytes 1024-163110536/163110537", total - 1024, "application/octet-stream", 1024L, total)
        )
        assertNull(rangeResponseStart(206, "bytes 0-163110536/163110537", total, null, 1024L, total))
        assertNull(rangeResponseStart(206, "bytes 1024-2047/999", 1024, null, 1024L, total))
        assertNull(rangeResponseStart(206, null, 1024, null, 1024L, total))
        assertNull(rangeResponseStart(416, "bytes */163110537", 0, null, total, total))
    }

    @Test
    fun `full response restarts from zero unless it is an error page or the wrong size`() {
        val total = 163110537L
        assertEquals(0L, rangeResponseStart(200, null, total, "application/vnd.android.package-archive", 1024L, total))
        assertEquals(0L, rangeResponseStart(200, null, -1, null, 0L, total))
        assertNull(rangeResponseStart(200, null, -1, "text/html; charset=utf-8", 0L, total))
        assertNull(rangeResponseStart(200, null, 512, null, 0L, total))
    }
}
