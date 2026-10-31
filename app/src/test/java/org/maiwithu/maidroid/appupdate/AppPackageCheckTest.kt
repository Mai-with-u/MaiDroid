package org.maiwithu.maidroid.appupdate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppPackageCheckTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val releaseCert = "04adb9b0b7714da34a16ecabc400633647d63de6cc805d19c38fa1ac384ee286"
    private val debugCert = "1111111111111111111111111111111111111111111111111111111111111111"
    private val installed = AppPackageIdentity("org.maiwithu.maidroid", 3, setOf(releaseCert))

    @Test
    fun `only builds signed with the release certificate update in place`() {
        assertTrue(canInstallOverCurrent(releaseCert.uppercase(), setOf(releaseCert)))
        assertFalse(canInstallOverCurrent(releaseCert, setOf(debugCert)))
        // 老版本 Release 没写证书、或读不出自己的签名时不拦，下载后再按安装包核对
        assertTrue(canInstallOverCurrent(null, setOf(debugCert)))
        assertTrue(canInstallOverCurrent(releaseCert, emptySet()))
    }

    @Test
    fun `downloaded package must be a newer maidroid with the same signer`() {
        assertNull(downloadedPackageProblem(installed.copy(versionCode = 4), installed))
        assertNotNull(downloadedPackageProblem(null, installed))
        assertNotNull(downloadedPackageProblem(installed.copy(packageName = "com.example", versionCode = 4), installed))
        assertNotNull(downloadedPackageProblem(installed, installed))
        assertNotNull(downloadedPackageProblem(installed.copy(versionCode = 4, signerSha256 = setOf(debugCert)), installed))
        // 系统读不出安装包签名时交给安装器自己判断
        assertNull(downloadedPackageProblem(installed.copy(versionCode = 4, signerSha256 = emptySet()), installed))
    }

    @Test
    fun `file sha256 matches the known digest`() {
        val file = temp.newFile("a.bin").apply { writeText("abc") }

        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", fileSha256(file))
    }
}
