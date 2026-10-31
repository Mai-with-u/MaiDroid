package org.maiwithu.maidroid.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContainerDocumentsTest {

    private val hostRoot = "/data/user/0/org.maiwithu.maidroid/files/debian"
    private val legacyHostRoot = "/data/data/org.maiwithu.maidroid/files/debian"
    private val aliases = listOf(hostRoot, legacyHostRoot)

    // 用一张“相对路径 -> 链接目标”的表模拟容器里的符号链接
    private fun resolve(path: String, links: Map<String, String>): String? =
        resolveContainerSymlinks(path, aliases) { links[it] }

    @Test
    fun `document id and relative path convert both ways`() {
        assertEquals("container:", containerDocumentId(""))
        assertEquals("container:opt/MaiBot/logs", containerDocumentId("opt/MaiBot/logs"))
        assertEquals("", containerRelativePath("container:"))
        assertEquals("opt/MaiBot/logs/app.log.jsonl", containerRelativePath("container:opt/MaiBot/logs/app.log.jsonl"))
        assertEquals(".venv/bin", containerRelativePath("container:.venv/bin"))
    }

    @Test
    fun `malformed or escaping document ids are rejected`() {
        listOf(
            "opt/MaiBot",
            "maibot:logs",
            "container:/opt/MaiBot",
            "container:opt/MaiBot/",
            "container:opt//MaiBot",
            "container:..",
            "container:opt/../../shared_prefs",
            "container:opt/./MaiBot",
            "container:opt/MaiBot\u0000/logs"
        ).forEach { assertNull(it, containerRelativePath(it)) }
    }

    @Test
    fun `child and parent paths`() {
        assertEquals("opt", childRelativePath("", "opt"))
        assertEquals("opt/MaiBot", childRelativePath("opt", "MaiBot"))
        assertNull(parentRelativePath(""))
        assertEquals("", parentRelativePath("opt"))
        assertEquals("opt/MaiBot", parentRelativePath("opt/MaiBot/logs"))
    }

    @Test
    fun `descendant check follows path segments, not string prefixes`() {
        assertTrue(isDescendantPath("", "opt"))
        assertTrue(isDescendantPath("opt/MaiBot", "opt/MaiBot/logs/app.log"))
        assertFalse(isDescendantPath("opt/MaiBot", "opt/MaiBot"))
        assertFalse(isDescendantPath("", ""))
        assertFalse(isDescendantPath("opt/MaiBot", "opt/MaiBot2/logs"))
        assertFalse(isDescendantPath("opt/MaiBot/logs", "opt/MaiBot"))
    }

    @Test
    fun `display names must be a single plain segment`() {
        assertTrue(isValidDisplayName("新建文件夹"))
        assertTrue(isValidDisplayName(".env"))
        listOf("", " ", ".", "..", "a/b", "a\u0000b").forEach { assertFalse(it, isValidDisplayName(it)) }
    }

    @Test
    fun `proot link2symlink stand-ins are hidden`() {
        assertTrue(isProotInternalEntry(".l2s.perl.dpkg-new0001"))
        assertTrue(isProotInternalEntry(".l2s.tmp_pack_0nu2VK0001.0001"))
        assertFalse(isProotInternalEntry(".git"))
        assertFalse(isProotInternalEntry("l2s.txt"))
    }

    @Test
    fun `host paths map into the container only under a root alias`() {
        assertEquals("", relativeToHostRoot(hostRoot, aliases))
        assertEquals("usr/bin/.l2s.perl0001", relativeToHostRoot("$legacyHostRoot/usr/bin/.l2s.perl0001", aliases))
        assertEquals("etc", relativeToHostRoot("$hostRoot/etc", listOf("$hostRoot/")))
        assertNull(relativeToHostRoot("${hostRoot}2/etc", aliases))
        assertNull(relativeToHostRoot("/sdcard", aliases))
        assertNull(relativeToHostRoot("/data/user/0/org.maiwithu.maidroid/shared_prefs", aliases))
    }

    @Test
    fun `plain paths resolve to themselves`() {
        assertEquals("", resolve("", emptyMap()))
        assertEquals("opt/MaiBot/logs", resolve("opt/MaiBot/logs", emptyMap()))
    }

    @Test
    fun `relative links resolve next to the link`() {
        val links = mapOf("opt/MaiBot/CLAUDE.md" to "AGENTS.md", "bin" to "usr/bin", "usr/lib64" to "../lib")
        assertEquals("opt/MaiBot/AGENTS.md", resolve("opt/MaiBot/CLAUDE.md", links))
        assertEquals("usr/bin/python3", resolve("bin/python3", links))
        assertEquals("lib/ld-linux.so", resolve("usr/lib64/ld-linux.so", links))
        // 链接后面的 ".." 从链接目标往上走，和内核一致
        assertEquals("usr/lib", resolve("bin/../lib", links))
    }

    @Test
    fun `absolute links are container paths, so they never leave the container`() {
        val links = mapOf(
            "etc/localtime" to "/usr/share/zoneinfo/Etc/UTC",
            "root/storage/shared" to "/sdcard",
            "opt/MaiBot/.venv/bin/python" to "/usr/bin/python3",
            "usr/bin/python3" to "python3.11"
        )
        assertEquals("usr/share/zoneinfo/Etc/UTC", resolve("etc/localtime", links))
        // 指向手机共享存储的链接落在容器里不存在的 sdcard，不会读到宿主上的文件
        assertEquals("sdcard/DCIM", resolve("root/storage/shared/DCIM", links))
        assertEquals("usr/bin/python3.11", resolve("opt/MaiBot/.venv/bin/python", links))
    }

    @Test
    fun `link2symlink targets written as host paths map back into the container`() {
        val links = mapOf(
            "usr/bin/perl" to "$legacyHostRoot/usr/bin/.l2s.perl.dpkg-new0001",
            "usr/bin/.l2s.perl.dpkg-new0001" to "$legacyHostRoot/usr/bin/.l2s.perl.dpkg-new0001.0002"
        )
        assertEquals("usr/bin/.l2s.perl.dpkg-new0001.0002", resolve("usr/bin/perl", links))
    }

    @Test
    fun `dot-dot stops at the container root like chroot`() {
        val links = mapOf("etc/mtab" to "../proc/self/mounts", "root/escape" to "../../../../../data")
        assertEquals("proc/self/mounts", resolve("etc/mtab", links))
        assertEquals("data/user", resolve("root/escape/user", links))
        assertEquals("", resolve("../../..", links))
    }

    @Test
    fun `symlink loops give up instead of spinning`() {
        assertNull(resolve("a", mapOf("a" to "b", "b" to "a")))
        assertNull(resolve("self/x", mapOf("self" to "self")))
        // 40 层以内的长链正常解析
        val chain = (0 until MAX_SYMLINK_HOPS).associate { "l$it" to "l${it + 1}" }
        assertEquals("l$MAX_SYMLINK_HOPS", resolve("l0", chain))
    }

    @Test
    fun `logs, configs and plugin sources open as plain text`() {
        val lookup: (String) -> String? = { mapOf("png" to "image/png", "json" to "application/json")[it] }
        listOf(
            "app_20261028_112444.log.jsonl",
            "bot_config.toml",
            "_manifest.json",
            "plugin.py",
            "README.MD",
            ".env",
            ".bashrc"
        ).forEach { assertEquals(it, "text/plain", containerFileMimeType(it, lookup)) }
        assertEquals("image/png", containerFileMimeType("avatar.png", lookup))
        assertEquals("application/octet-stream", containerFileMimeType("libnapcat_launcher.so", lookup))
        assertEquals("application/octet-stream", containerFileMimeType("Dockerfile", lookup))
        assertEquals("application/octet-stream", containerFileMimeType("trailing.", lookup))
    }

    @Test
    fun `new names never clash with existing entries`() {
        val existing = setOf("新建文件夹", "新建文件夹 (1)", "notes.txt", ".env")
        assertEquals("plugins", uniqueChildName("plugins", isDirectory = true) { it in existing })
        assertEquals("新建文件夹 (2)", uniqueChildName("新建文件夹", isDirectory = true) { it in existing })
        assertEquals("notes (1).txt", uniqueChildName("notes.txt", isDirectory = false) { it in existing })
        assertEquals(".env (1)", uniqueChildName(".env", isDirectory = false) { it in existing })
    }
}
