package org.maiwithu.maidroid.maibot.update

import org.junit.Assert.assertFalse
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

    @Test
    fun `where the update is heading is recorded before the source is touched`() {
        val body = MaiBotUpdateScripts.fetchAndReset().body
        val record = body.indexOf("> '/root/.maidroid/maibot-update-pending.tmp'")
        assertTrue(record > 0)
        // 写临时文件再改名，记录不会只写一半；记下之后才动源码
        assertTrue(body.indexOf("mv '/root/.maidroid/maibot-update-pending.tmp' '/root/.maidroid/maibot-update-pending'") > record)
        assertTrue(body.indexOf("git reset --hard") > record)
        assertTrue(body.indexOf("git checkout --force --no-track -B") > record)
    }

    @Test
    fun `an interrupted update is finished offline from the record`() {
        val body = MaiBotUpdateScripts.resumeInterrupted().body
        assertTrue(body.contains("read -r pending_branch pending_commit < '/root/.maidroid/maibot-update-pending'"))
        // 那个提交不在本地就不动源码，交给用户重新更新
        assertTrue(body.contains("git cat-file -e \"${'$'}pending_commit^{commit}\""))
        assertTrue(body.contains("exit 5"))
        assertTrue(body.contains("git checkout --force --no-track -B \"${'$'}branch\" \"${'$'}pending_commit\""))
        // 不联网，也会先清掉打断时留下的锁
        assertFalse(body.contains("maidroid_github_fetch"))
        assertTrue(body.indexOf("find .git -name '*.lock'") < body.indexOf("git checkout --force"))
    }
}
