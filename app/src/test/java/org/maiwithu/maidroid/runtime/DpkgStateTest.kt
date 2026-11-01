package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DpkgStateTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun rootfs(status: String, updates: List<String> = emptyList()): File {
        val rootfs = temp.newFolder("rootfs")
        val dpkg = File(rootfs, "var/lib/dpkg").apply { mkdirs() }
        File(dpkg, "status").writeText(status)
        File(dpkg, "updates").mkdirs()
        updates.forEach { File(dpkg, "updates/$it").writeText("Package: git\n") }
        return rootfs
    }

    @Test
    fun `fully installed packages are fine`() {
        val rootfs = rootfs(
            "Package: git\nStatus: install ok installed\n\nPackage: old\nStatus: deinstall ok config-files\n"
        )

        assertFalse(DpkgState.isInterrupted(rootfs))
    }

    @Test
    fun `killed while setting up ca-certificates`() {
        // 2026-11 真机强杀在“Setting up ca-certificates”时留下的样子：updates 里有没并进 status 的记录
        val rootfs = rootfs("Package: git\nStatus: install ok installed\n", updates = listOf("0000", "0001"))

        assertTrue(DpkgState.isInterrupted(rootfs))
    }

    @Test
    fun `packages left unpacked or half configured`() {
        listOf("unpacked", "half-configured", "half-installed").forEach { state ->
            temp.root.listFiles()?.forEach { it.deleteRecursively() }
            val rootfs = rootfs("Package: ca-certificates\nStatus: install ok $state\n")

            assertTrue(state, DpkgState.isInterrupted(rootfs))
        }
    }

    @Test
    fun `missing dpkg database is not treated as interrupted`() {
        assertFalse(DpkgState.isInterrupted(temp.newFolder("empty")))
    }
}
