package org.maiwithu.maidroid.maibot.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaiBotUpdateReportTest {
    private val oldCommit = "c602a2780aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val newCommit = "ad2b474ddbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

    @Test
    fun `report is parsed from noisy script output`() {
        val report = parseMaiBotUpdateReport(
            """
            [MaiBotUpdate] fetching main from upstream
            [GitHub] git fetch via gitproxy.mrhjx.cn (attempt 1/3): https://example/MaiBot.git
            From https://example/MaiBot
            MAIDROID_UPDATE_BRANCH=main
            MAIDROID_UPDATE_INSTALLED=$oldCommit${'\t'}1792600000${'\t'}Update maibot-dashboard version to 1.9.8
            MAIDROID_UPDATE_LATEST=$newCommit${'\t'}1792663038${'\t'}Update pyproject.toml
            MAIDROID_UPDATE_LOCAL_CHANGES=2
            """.trimIndent()
        )

        assertEquals("main", report.branch)
        assertEquals(oldCommit, report.installed?.commit)
        assertEquals("c602a27", report.installed?.shortCommit)
        assertEquals(1792663038L, report.latest?.committedAtEpochSeconds)
        assertEquals("Update pyproject.toml", report.latest?.subject)
        assertEquals(2, report.localChangeCount)
        assertTrue(report.hasUpdate)
    }

    @Test
    fun `last value wins when the installed revision is printed again after reset`() {
        val report = parseMaiBotUpdateReport(
            listOf(
                "MAIDROID_UPDATE_INSTALLED=$oldCommit\t1792600000\told",
                "[MaiBotUpdate] resetting to ad2b474",
                "MAIDROID_UPDATE_INSTALLED=$newCommit\t1792663038\tnew",
                "MAIDROID_UPDATE_LATEST=$newCommit\t1792663038\tnew"
            ).joinToString("\r\n")
        )

        assertEquals(newCommit, report.installed?.commit)
        assertFalse(report.hasUpdate)
    }

    @Test
    fun `missing keys stay empty instead of defaulting`() {
        val report = parseMaiBotUpdateReport("[GitHub] git fetch failed after all mirrors")

        assertNull(report.branch)
        assertNull(report.installed)
        assertNull(report.latest)
        assertNull(report.localChangeCount)
        assertFalse(report.hasUpdate)
    }

    @Test
    fun `revision with empty subject is still valid`() {
        val revision = parseMaiBotRevision("${oldCommit.uppercase()}\t1792600000\t")

        assertEquals(oldCommit, revision?.commit)
        assertEquals("", revision?.subject)
    }

    @Test
    fun `malformed revisions are rejected`() {
        assertNull(parseMaiBotRevision(""))
        assertNull(parseMaiBotRevision("fatal: bad revision 'HEAD'"))
        assertNull(parseMaiBotRevision("c602a27\tnot-a-time\tsubject"))
        assertNull(parseMaiBotRevision("xyz1234567\t1792600000\tsubject"))
    }

    @Test
    fun `schema versions are parsed and blank values stay unknown`() {
        val report = parseMaiBotUpdateReport(
            "MAIDROID_UPDATE_TARGET_SCHEMA=46\nMAIDROID_UPDATE_DATABASE_SCHEMA=47\n"
        )
        assertEquals(46, report.targetSchemaVersion)
        assertEquals(47, report.databaseSchemaVersion)
        assertTrue(report.wouldDowngradeDatabase)

        // 脚本读不到时打印空值：不当成 0，也不拦
        val unknown = parseMaiBotUpdateReport("MAIDROID_UPDATE_TARGET_SCHEMA=46\nMAIDROID_UPDATE_DATABASE_SCHEMA=\n")
        assertNull(unknown.databaseSchemaVersion)
        assertFalse(unknown.wouldDowngradeDatabase)
    }

    @Test
    fun `same or older database schema is not a downgrade`() {
        val base = MaiBotUpdateReport(branch = "main", installed = null, latest = null, localChangeCount = 0)
        assertFalse(base.copy(targetSchemaVersion = 46, databaseSchemaVersion = 46).wouldDowngradeDatabase)
        assertFalse(base.copy(targetSchemaVersion = 47, databaseSchemaVersion = 46).wouldDowngradeDatabase)
        assertNull(maiBotDatabaseDowngradeMessage(base.copy(targetSchemaVersion = 46), MaiBotChannel.Stable))
    }

    @Test
    fun `downgrade message names the versions and what happens`() {
        val report = MaiBotUpdateReport(
            branch = "main",
            installed = null,
            latest = null,
            localChangeCount = 0,
            targetSchemaVersion = 46,
            databaseSchemaVersion = 47
        )

        assertEquals(
            "麦麦的数据库已是第 47 版，正式版只支持到第 46 版，MaiBot 不支持数据库降级。" +
                "切过去麦麦会启动失败，已取消切换，等正式版跟上后再切。",
            maiBotDatabaseDowngradeMessage(report, MaiBotChannel.Stable)
        )
        assertEquals(
            "麦麦的数据库已是第 47 版，上游最新版本只支持到第 46 版，MaiBot 不支持数据库降级。" +
                "上游可能回退了提交，更新后麦麦会启动失败，暂不更新，等上游跟上后再更新。",
            maiBotDatabaseDowngradeMessage(report, target = null)
        )
    }
}
