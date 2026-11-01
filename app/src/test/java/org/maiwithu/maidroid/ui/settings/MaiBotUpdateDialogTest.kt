package org.maiwithu.maidroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.maibot.update.MaiBotChannel
import org.maiwithu.maidroid.maibot.update.MaiBotRevision
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateAction
import org.maiwithu.maidroid.maibot.update.MaiBotUpdatePhase
import org.maiwithu.maidroid.maibot.update.MaiBotUpdateState
import java.time.ZoneOffset

class MaiBotUpdateDialogTest {
    private val installed = MaiBotRevision(
        commit = "c602a2780aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        committedAtEpochSeconds = 1_792_663_038L,
        subject = "Update maibot-dashboard version to 1.9.8"
    )
    private val latest = installed.copy(commit = "ad2b474ddbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")

    @Test
    fun `available update offers update and restart`() {
        val button = maiBotUpdatePrimaryButton(
            MaiBotUpdateState(phase = MaiBotUpdatePhase.Available, installed = installed, latest = latest)
        )

        assertEquals(MaiBotUpdateButtonAction.Apply, button.action)
        assertEquals("更新并重启", button.label)
    }

    @Test
    fun `failed apply retries the update instead of checking again`() {
        val button = maiBotUpdatePrimaryButton(
            MaiBotUpdateState(
                phase = MaiBotUpdatePhase.Failed,
                failedAction = MaiBotUpdateAction.Apply,
                failure = Failure(FailureStep.UvSync, "依赖同步失败")
            )
        )

        assertEquals(MaiBotUpdateButtonAction.Apply, button.action)
    }

    @Test
    fun `failed check checks again`() {
        val button = maiBotUpdatePrimaryButton(
            MaiBotUpdateState(phase = MaiBotUpdatePhase.Failed, failedAction = MaiBotUpdateAction.Check)
        )

        assertEquals(MaiBotUpdateButtonAction.Check, button.action)
    }

    @Test
    fun `busy phases disable the primary button`() {
        assertFalse(maiBotUpdatePrimaryButton(MaiBotUpdateState(phase = MaiBotUpdatePhase.Checking)).enabled)
        assertFalse(maiBotUpdatePrimaryButton(MaiBotUpdateState(phase = MaiBotUpdatePhase.Updating)).enabled)
    }

    @Test
    fun `idle summary shows installed commit and time`() {
        val summary = maiBotUpdateSummaryText(
            MaiBotUpdateState(installed = installed),
            zoneId = ZoneOffset.UTC
        )

        assertEquals("当前 c602a27 · 2026-10-22 09:57", summary)
    }

    @Test
    fun `summary falls back when revisions are unknown`() {
        assertEquals("从上游仓库拉取最新版本", maiBotUpdateSummaryText(MaiBotUpdateState()))
        assertEquals("发现新版本", maiBotUpdateSummaryText(MaiBotUpdateState(phase = MaiBotUpdatePhase.Available)))
        assertEquals(
            "发现新版本 ad2b474",
            maiBotUpdateSummaryText(MaiBotUpdateState(phase = MaiBotUpdatePhase.Available, latest = latest))
        )
    }

    @Test
    fun `updating summary shows current step`() {
        val summary = maiBotUpdateSummaryText(
            MaiBotUpdateState(phase = MaiBotUpdatePhase.Updating, progressText = "正在同步 Python 依赖")
        )

        assertEquals("正在同步 Python 依赖", summary)
    }

    @Test
    fun `summary puts the channel before the commit`() {
        assertEquals(
            "测试版 c602a27 · 2026-10-22 09:57",
            maiBotUpdateSummaryText(MaiBotUpdateState(branch = "dev", installed = installed), zoneId = ZoneOffset.UTC)
        )
        assertEquals(
            "已是最新 · 正式版 c602a27",
            maiBotUpdateSummaryText(
                MaiBotUpdateState(phase = MaiBotUpdatePhase.UpToDate, branch = "main", installed = installed)
            )
        )
        // 经容器 SSH 手动切到的其它分支原样显示分支名
        assertEquals(
            "已是最新 · feature-x c602a27",
            maiBotUpdateSummaryText(
                MaiBotUpdateState(phase = MaiBotUpdatePhase.UpToDate, branch = "feature-x", installed = installed)
            )
        )
    }

    @Test
    fun `switching shows switch wording instead of update wording`() {
        val switching = MaiBotUpdateState(phase = MaiBotUpdatePhase.Updating, switchTarget = MaiBotChannel.Testing)
        assertEquals("切换中...", maiBotUpdatePrimaryButton(switching).label)
        assertFalse(maiBotUpdatePrimaryButton(switching).enabled)

        val switched = MaiBotUpdateState(
            phase = MaiBotUpdatePhase.Updated,
            branch = "dev",
            installed = installed,
            switchTarget = MaiBotChannel.Testing
        )
        assertEquals("已切换到测试版 · c602a27", maiBotUpdateSummaryText(switched))
        assertEquals("已切换到测试版，麦麦正在重启。", maiBotUpdateStatusText(switched))
    }

    @Test
    fun `failed switch retries switching to the same channel`() {
        val failed = MaiBotUpdateState(
            phase = MaiBotUpdatePhase.Failed,
            failedAction = MaiBotUpdateAction.Switch,
            failure = Failure(FailureStep.UpdateReset, "源码更新失败：exit 1"),
            switchTarget = MaiBotChannel.Stable
        )

        val button = maiBotUpdatePrimaryButton(failed)
        assertEquals(MaiBotUpdateButtonAction.Switch, button.action)
        assertEquals("重试切换", button.label)
        assertEquals("切换失败：源码更新失败：exit 1", maiBotUpdateSummaryText(failed))
    }

    @Test
    fun `failed switch without a target falls back to checking again`() {
        val button = maiBotUpdatePrimaryButton(
            MaiBotUpdateState(phase = MaiBotUpdatePhase.Failed, failedAction = MaiBotUpdateAction.Switch)
        )

        assertEquals(MaiBotUpdateButtonAction.Check, button.action)
    }

    @Test
    fun `switch prompts warn about the risk of each channel`() {
        val testing = maiBotChannelSwitchPrompt(MaiBotChannel.Testing)
        assertEquals("切换到测试版？", testing.title)
        assertTrue(testing.message.contains("回退提交"))
        assertTrue(testing.message.contains("dev"))

        val stable = maiBotChannelSwitchPrompt(MaiBotChannel.Stable)
        assertEquals("切换到正式版？", stable.title)
        assertTrue(stable.message.contains("取消切换"))
        assertTrue(stable.message.contains("main"))
    }
}
