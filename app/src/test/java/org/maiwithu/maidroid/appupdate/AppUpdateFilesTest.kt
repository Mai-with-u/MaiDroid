package org.maiwithu.maidroid.appupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import org.maiwithu.maidroid.VersionNumber

class AppUpdateFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val v012 = VersionNumber.parse("0.1.2")!!
    private val v013 = VersionNumber.parse("0.1.3")!!
    private val v014 = VersionNumber.parse("0.1.4")!!

    @Test
    fun `file names carry the version and round trip`() {
        val files = AppUpdateFiles(temp.root)

        assertEquals("MaiDroid-0.1.3.apk", files.apkFile(v013).name)
        assertEquals("MaiDroid-0.1.3.apk.part", files.partFile(v013).name)
        assertEquals(v013, AppUpdateFiles.versionOf("MaiDroid-0.1.3.apk.part"))
        assertEquals(VersionNumber.parse("0.2.0-rc1"), AppUpdateFiles.versionOf("MaiDroid-0.2.0-rc1.apk"))
        assertNull(AppUpdateFiles.versionOf("MaiDroid_v0.1.3.apk"))
        assertNull(AppUpdateFiles.versionOf("MaiDroid-0.1.3.zip"))
    }

    @Test
    fun `cleanup keeps only newer versions`() {
        val files = AppUpdateFiles(temp.root)
        files.dir.mkdirs()
        listOf(files.apkFile(v012), files.partFile(v013), files.apkFile(v014)).forEach { it.writeText("x") }
        File(files.dir, "junk.tmp").writeText("x")

        files.deleteInstalledOrUnknown(v012)
        assertEquals(setOf("MaiDroid-0.1.3.apk.part", "MaiDroid-0.1.4.apk"), files.dir.list()!!.toSet())

        files.deleteOtherVersions(v014)
        assertEquals(setOf("MaiDroid-0.1.4.apk"), files.dir.list()!!.toSet())
    }
}
