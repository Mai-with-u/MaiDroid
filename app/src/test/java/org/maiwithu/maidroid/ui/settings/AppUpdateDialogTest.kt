package org.maiwithu.maidroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.appupdate.AppDownloadProgress
import org.maiwithu.maidroid.appupdate.AppRelease
import org.maiwithu.maidroid.appupdate.AppReleaseAsset
import org.maiwithu.maidroid.appupdate.AppUpdateAction
import org.maiwithu.maidroid.appupdate.AppUpdatePhase
import org.maiwithu.maidroid.appupdate.AppUpdateState
import org.maiwithu.maidroid.appupdate.AppVersion

class AppUpdateDialogTest {
    private val release = AppRelease(
        tag = "v0.1.3",
        version = AppVersion.parse("0.1.3")!!,
        pageUrl = "https://github.com/Mai-with-u/MaiDroid/releases/tag/v0.1.3",
        publishedAtEpochSeconds = null,
        notes = "### 新增\n\n- 检查更新",
        apk = AppReleaseAsset("MaiDroid_v0.1.3.apk", 163_110_537L, "https://example.com/a.apk"),
        apkSha256 = null,
        certSha256 = null
    )
    private val available = AppUpdateState(
        phase = AppUpdatePhase.Available,
        currentVersion = "0.1.2",
        release = release
    )

    private fun button(state: AppUpdateState, granted: Boolean = true) = appUpdatePrimaryButton(state, granted)

    @Test
    fun `new version offers download then resume after a partial download`() {
        assertEquals(AppUpdateButton("下载并安装", AppUpdateButtonAction.Download), button(available))
        assertEquals("继续下载", button(available.copy(partialBytes = 1024)).label)
    }

    @Test
    fun `cleared installer package explains why it has to download again`() {
        val cleared = available.copy(errorMessage = "安装包已被系统清理，请重新下载")

        assertEquals("安装包已被系统清理，请重新下载", appUpdateStatusText(cleared, true))
        assertEquals(AppUpdateButtonAction.Download, button(cleared).action)
    }

    @Test
    fun `builds with a different signer only open the release page`() {
        val debugBuild = available.copy(canInstallInApp = false)

        assertEquals(AppUpdateButtonAction.OpenReleasePage, button(debugBuild).action)
        assertTrue(appUpdateStatusText(debugBuild, true).contains("签名和发布版不同"))
    }

    @Test
    fun `downloading can be cancelled and busy phases disable the button`() {
        assertEquals(
            AppUpdateButtonAction.CancelDownload,
            button(available.copy(phase = AppUpdatePhase.Downloading)).action
        )
        assertFalse(button(available.copy(phase = AppUpdatePhase.Checking)).enabled)
        assertFalse(button(available.copy(phase = AppUpdatePhase.Verifying)).enabled)
    }

    @Test
    fun `install asks for the unknown sources permission first`() {
        val ready = available.copy(phase = AppUpdatePhase.ReadyToInstall)

        assertEquals(AppUpdateButtonAction.Install, button(ready, granted = true).action)
        assertEquals(AppUpdateButtonAction.GrantInstallPermission, button(ready, granted = false).action)
        assertTrue(appUpdateStatusText(ready, false).contains("安装未知应用"))
    }

    @Test
    fun `failures retry the step that failed`() {
        val failed = available.copy(phase = AppUpdatePhase.Failed, errorMessage = "下载失败")

        assertEquals(AppUpdateButtonAction.Download, button(failed.copy(failedAction = AppUpdateAction.Download)).action)
        assertEquals(AppUpdateButtonAction.Install, button(failed.copy(failedAction = AppUpdateAction.Install)).action)
        assertEquals(AppUpdateButtonAction.Check, button(failed.copy(failedAction = AppUpdateAction.Check)).action)
    }

    @Test
    fun `settings row summary follows the update phase`() {
        assertEquals("v0.1.2 · 点击检查更新", appUpdateSummaryText(AppUpdateState(currentVersion = "0.1.2")))
        assertEquals("v0.1.2 · 发现新版本 v0.1.3", appUpdateSummaryText(available))
        assertEquals(
            "正在下载 v0.1.3 · 45%",
            appUpdateSummaryText(
                available.copy(
                    phase = AppUpdatePhase.Downloading,
                    progress = AppDownloadProgress(45, 100, 0, "gh-proxy.com")
                )
            )
        )
        assertEquals("v0.1.3 已下载，点击安装", appUpdateSummaryText(available.copy(phase = AppUpdatePhase.ReadyToInstall)))
    }

    @Test
    fun `progress line shows percent sizes speed and source`() {
        val progress = AppDownloadProgress(
            downloadedBytes = 73_400_320L,
            totalBytes = 163_110_537L,
            bytesPerSecond = 2_202_009L,
            source = "gh-proxy.com"
        )

        assertEquals("45% · 70.0 MB / 155.6 MB · 2.1 MB/s · 经 gh-proxy.com", appDownloadProgressText(progress))
        assertTrue(appDownloadProgressText(progress.copy(source = null)).contains("选出最快的一条"))
    }
}
