package org.maiwithu.maidroid.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DurableFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `atomic write replaces the whole file and leaves no temp file`() {
        val dir = temp.newFolder("plugin")
        val config = File(dir, "config.toml")
        config.writeText("old = 1\n")

        config.writeTextAtomically("new = 2\n")

        assertEquals("new = 2\n", config.readText())
        assertEquals(listOf("config.toml"), dir.list()!!.toList())
    }

    @Test
    fun `atomic write creates missing parent directories`() {
        val config = File(temp.root, "a/b/config.toml")

        config.writeTextAtomically("x")

        assertEquals("x", config.readText())
    }

    @Test
    fun `failed write keeps the old content and cleans up`() {
        // 目标位置是个非空目录，改名盖不过去：模拟写不成的情况
        val dir = temp.newFolder("plugin")
        val target = File(dir, "config.toml")
        target.mkdirs()
        File(target, "keep").writeText("old")

        val failed = runCatching { target.writeTextAtomically("new") }.isFailure

        assertTrue(failed)
        assertEquals("old", File(target, "keep").readText())
        assertFalse(File(dir, "config.toml.maidroid.tmp").exists())
    }

    @Test
    fun `sizes of partly downloaded caches are added up`() {
        val cache = temp.newFolder("uv")
        File(cache, "a.whl").writeBytes(ByteArray(1000))
        File(cache, "sub").mkdirs()
        File(cache, "sub/b.py").writeBytes(ByteArray(24))

        assertEquals(1024L, StorageSpace.sizeOf(cache))
        assertEquals(0L, StorageSpace.sizeOf(File(temp.root, "missing")))
    }
}
