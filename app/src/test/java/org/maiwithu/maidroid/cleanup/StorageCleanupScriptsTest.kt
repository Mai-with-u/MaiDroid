package org.maiwithu.maidroid.cleanup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageCleanupScriptsTest {
    // 2026-11 调试机上 measure 脚本的原样输出（KB）
    @Test
    fun `sizes are read from measure output in bytes`() {
        val sizes = parseCleanupSizes(
            """
            size.dependency_cache=1552711
            size.apt_cache=324322
            size.unused_python=98025
            size.napcat_backups=0
            size.maibot_old_logs=
            size.unknown=5
            """.trimIndent()
        )

        assertEquals(1552711L * 1024, sizes[CleanupItem.DependencyCache])
        assertEquals(0L, sizes[CleanupItem.NapCatBackups])
        // 没量到的不算 0，界面显示“大小未知”
        assertFalse(CleanupItem.OldMaiBotLogs in sizes)
        assertEquals(4, sizes.size)
    }

    @Test
    fun `skipped items are read from clean output`() {
        val skipped = parseSkippedCleanup(
            """
            skipped=dependency_cache
            [清理] 依赖包下载缓存：正在装依赖，这次先跳过
            [清理] 已清理：软件源缓存
            """.trimIndent()
        )

        assertEquals(setOf(CleanupItem.DependencyCache), skipped)
    }

    @Test
    fun `clean only touches selected items and measures again afterwards`() {
        val body = StorageCleanupScripts.clean(setOf(CleanupItem.AptCache)).body

        assertTrue(body.contains("rm -rf /var/lib/apt/lists/*"))
        assertFalse(body.contains("rm -rf /root/.cache/uv"))
        assertFalse(body.contains("rm -rf /root/napcat.maidroid-bak.*"))
        // 删完再量一遍，结果页用删后的大小
        CleanupItem.entries.forEach { assertTrue(body.contains("size.${it.key}=")) }
    }

    @Test
    fun `caches are skipped while uv or apt is working and logs stay unchecked by default`() {
        val body = StorageCleanupScripts.clean(CleanupItem.entries.toSet()).body

        assertTrue(body.contains("if maidroid_busy uv 'sync|pip|python|lock|venv|add|remove'; then"))
        assertTrue(body.contains("if maidroid_busy 'apt|apt-get|dpkg' ''; then"))
        assertFalse(CleanupItem.OldMaiBotLogs.selectedByDefault)
        assertTrue(CleanupItem.DependencyCache.selectedByDefault)
    }
}
