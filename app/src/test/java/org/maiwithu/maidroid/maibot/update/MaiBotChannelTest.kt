package org.maiwithu.maidroid.maibot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiBotChannelTest {
    @Test
    fun `channels map to upstream main and dev`() {
        assertEquals(MaiBotChannel.Stable, MaiBotChannel.ofBranch("main"))
        assertEquals(MaiBotChannel.Testing, MaiBotChannel.ofBranch("dev"))
        assertNull(MaiBotChannel.ofBranch("release/1.3.19"))
        assertNull(MaiBotChannel.ofBranch(null))
    }

    @Test
    fun `branch label names the channel for main and dev only`() {
        assertEquals("正式版（main）", maiBotBranchLabel("main"))
        assertEquals("测试版（dev）", maiBotBranchLabel("dev"))
        assertEquals("feature-x", maiBotBranchLabel("feature-x"))
    }

    @Test
    fun `state channel follows the branch read from the repository`() {
        assertEquals(MaiBotChannel.Testing, MaiBotUpdateState(branch = "dev").channel)
        assertNull(MaiBotUpdateState().channel)
    }

    @Test
    fun `switching to a channel fetches and checks out its branch`() {
        val body = MaiBotUpdateScripts.fetchAndReset(MaiBotChannel.Testing.branch).body

        assertTrue(body.contains("branch='dev'"))
        assertTrue(body.contains("git checkout --force --no-track -B \"\$branch\" \"\$remote_ref\""))
        // 强制拉取（refspec 带 +）：上游 dev 回退提交后照样能把 origin/dev 拉回去
        assertTrue(body.contains("\"+refs/heads/\$branch:\$remote_ref\""))
    }

    @Test
    fun `plain update follows the branch the repository is on`() {
        val body = MaiBotUpdateScripts.fetchAndReset().body

        assertTrue(body.contains("branch=\"\$current_branch\""))
        assertFalse(body.contains("branch='"))
        assertTrue(body.contains("git reset --hard \"\$remote_ref\""))
    }

    @Test
    fun `reset refuses a database downgrade before touching the source`() {
        val body = MaiBotUpdateScripts.fetchAndReset(MaiBotChannel.Stable.branch).body

        // 先读两边的版本、比较、会降级就 exit 4，之后才是 checkout / reset
        val schema = body.indexOf("MAIDROID_UPDATE_DATABASE_SCHEMA")
        val refuse = body.indexOf("exit 4")
        val checkout = body.indexOf("git checkout --force")
        assertTrue(schema in 0 until refuse)
        assertTrue(refuse < checkout)
        assertTrue(body.contains("src/common/database/migrations/builtin.py"))
        assertTrue(body.contains("PRAGMA user_version"))
    }

    @Test
    fun `fetching for a precheck reports schema versions but never resets`() {
        val body = MaiBotUpdateScripts.fetchLatest(MaiBotChannel.Stable.branch).body

        assertTrue(body.contains("branch='main'"))
        assertTrue(body.contains("MAIDROID_UPDATE_TARGET_SCHEMA"))
        assertFalse(body.contains("git reset"))
        assertFalse(body.contains("git checkout"))
    }
}
