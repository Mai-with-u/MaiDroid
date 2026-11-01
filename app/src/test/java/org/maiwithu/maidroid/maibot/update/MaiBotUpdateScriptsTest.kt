package org.maiwithu.maidroid.maibot.update

import org.junit.Assert.assertTrue
import org.junit.Test

class MaiBotUpdateScriptsTest {
    @Test
    fun `stale git locks are cleared before fetching when no git is running`() {
        listOf(MaiBotUpdateScripts.fetchLatest(), MaiBotUpdateScripts.fetchAndReset()).forEach { script ->
            val body = script.body
            val cleanup = body.indexOf("find .git -name '*.lock'")
            assertTrue(cleanup > 0)
            // 先确认没有 git 在跑，再在拉取之前清锁
            assertTrue(body.indexOf("if ! maidroid_git_running; then") in 0 until cleanup)
            assertTrue(cleanup < body.indexOf("maidroid_github_fetch 'https://"))
        }
    }
}
