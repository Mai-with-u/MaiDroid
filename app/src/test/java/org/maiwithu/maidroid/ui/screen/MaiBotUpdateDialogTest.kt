package org.maiwithu.maidroid.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
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
                errorMessage = "依赖同步失败"
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
}
