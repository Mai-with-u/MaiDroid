package org.maiwithu.maidroid.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.shouldKeepScreenOn
import org.maiwithu.maidroid.backup.BackupConfig
import org.maiwithu.maidroid.backup.BackupState
import org.maiwithu.maidroid.backup.DEFAULT_EXTERNAL_DISPLAY_PATH
import org.maiwithu.maidroid.permission.PermissionState
import org.maiwithu.maidroid.service.shouldHoldWifiLock
import org.maiwithu.maidroid.runtime.AptMirrors
import org.maiwithu.maidroid.runtime.GitHubMirrors
import org.maiwithu.maidroid.runtime.MirrorCategory
import org.maiwithu.maidroid.runtime.MirrorPreference
import org.maiwithu.maidroid.runtime.MirrorSelectionMode
import org.maiwithu.maidroid.runtime.MirrorSettingsState
import org.maiwithu.maidroid.ui.component.HomeTwoPaneSpec

class SettingsPageTest {
    @Test
    fun `landscape window uses two panes when wide enough`() {
        assertTrue(settingsUseTwoPane(widthDp = 600f, heightDp = 360f))
        assertTrue(settingsUseTwoPane(widthDp = 520f, heightDp = 360f))
    }

    @Test
    fun `narrow landscape split window falls back to single pane`() {
        assertFalse(settingsUseTwoPane(widthDp = 480f, heightDp = 360f))
    }

    @Test
    fun `compact portrait window keeps single pane navigation`() {
        assertFalse(settingsUseTwoPane(widthDp = 412f, heightDp = 917f))
    }

    @Test
    fun `expanded portrait window uses two panes`() {
        assertTrue(settingsUseTwoPane(widthDp = 1000f, heightDp = 1200f))
    }

    @Test
    fun `navigation pane locks to unified 38 percent width across breakpoints`() {
        // 与消息平台、扩展组件页共享 HomeTwoPaneSpec 的 38% 左栏基准
        assertEquals(1280f * 0.38f, settingsNavigationWidthDp(1280f), 0.001f)
        assertEquals(HomeTwoPaneSpec.leftPaneWidthDp(1280f), settingsNavigationWidthDp(1280f), 0.001f)
        assertEquals(1920f * 0.38f, settingsNavigationWidthDp(1920f), 0.001f)
        assertEquals(HomeTwoPaneSpec.leftPaneWidthDp(1920f), settingsNavigationWidthDp(1920f), 0.001f)
        assertEquals(480f * 0.38f, settingsNavigationWidthDp(480f), 0.001f)
        assertEquals(HomeTwoPaneSpec.leftPaneWidthDp(480f), settingsNavigationWidthDp(480f), 0.001f)
    }

    @Test
    fun `two pane detail selection toggles back to centered empty icon while portrait enters detail`() {
        // 首次从默认空态（null）点击“权限管理”进入详情
        assertEquals(
            "permissions",
            resolveSettingsDetailSelection(currentDetail = null, targetDetail = "permissions", useTwoPane = true)
        )
        // 双栏模式下再次点击当前已选详情可收起回到右栏居中液态玻璃图标空态
        assertNull(
            resolveSettingsDetailSelection(
                currentDetail = "permissions",
                targetDetail = "permissions",
                useTwoPane = true
            )
        )
        // 竖屏单列模式下保持进入目标详情页
        assertEquals(
            "permissions",
            resolveSettingsDetailSelection(
                currentDetail = "permissions",
                targetDetail = "permissions",
                useTwoPane = false
            )
        )
    }

    @Test
    fun `two pane mirror detail toggles back to empty and switches cleanly with permissions`() {
        assertEquals(
            MirrorSettingsDetail,
            resolveSettingsDetailSelection(
                currentDetail = null,
                targetDetail = MirrorSettingsDetail,
                useTwoPane = true
            )
        )
        assertNull(
            resolveSettingsDetailSelection(
                currentDetail = MirrorSettingsDetail,
                targetDetail = MirrorSettingsDetail,
                useTwoPane = true
            )
        )
        assertEquals(
            MirrorSettingsDetail,
            resolveSettingsDetailSelection(
                currentDetail = "permissions",
                targetDetail = MirrorSettingsDetail,
                useTwoPane = true
            )
        )
        assertEquals(
            MirrorSettingsDetail,
            resolveSettingsDetailSelection(
                currentDetail = MirrorSettingsDetail,
                targetDetail = MirrorSettingsDetail,
                useTwoPane = false
            )
        )
    }

    @Test
    fun `mirrorSettingsSummaryText summarizes auto and manual selections accurately`() {
        val allAuto = MirrorSettingsState()
        assertEquals("自动测速优选（GitHub / Debian / PyPI）", mirrorSettingsSummaryText(allAuto))

        val partialManual = MirrorSettingsState(
            preferences = mapOf(
                MirrorCategory.GitHub to MirrorPreference(
                    mode = MirrorSelectionMode.Preset,
                    presetValue = "https://ghfast.top"
                ),
                MirrorCategory.Apt to MirrorPreference(),
                MirrorCategory.PyPi to MirrorPreference()
            )
        )
        assertEquals("手动：GitHub(ghfast.top) · 其余自动", mirrorSettingsSummaryText(partialManual))

        val allManual = MirrorSettingsState(
            preferences = mapOf(
                MirrorCategory.GitHub to MirrorPreference(
                    mode = MirrorSelectionMode.Preset,
                    presetValue = GitHubMirrors.ALL.last().prefix
                ),
                MirrorCategory.Apt to MirrorPreference(
                    mode = MirrorSelectionMode.Custom,
                    customUrl = "http://mirrors.example.com"
                ),
                MirrorCategory.PyPi to MirrorPreference(
                    mode = MirrorSelectionMode.Preset,
                    presetValue = ""
                )
            )
        )
        assertEquals("手动：GitHub(ghfast.top) · APT(自定义) · PyPI(MaiBot 自带的源)", mirrorSettingsSummaryText(allManual))
    }

    @Test
    fun `shouldKeepScreenOn forces keep-on during OOBE and follows setting after setup`() {
        assertTrue(shouldKeepScreenOn(setupComplete = false, keepScreenOnEnabled = false))
        assertTrue(shouldKeepScreenOn(setupComplete = false, keepScreenOnEnabled = true))
        assertFalse(shouldKeepScreenOn(setupComplete = true, keepScreenOnEnabled = false))
        assertTrue(shouldKeepScreenOn(setupComplete = true, keepScreenOnEnabled = true))
    }

    @Test
    fun `shouldHoldWifiLock requires user opt-in and releases on low battery or safe exit`() {
        assertFalse(shouldHoldWifiLock(wifiLockEnabled = false, lowBatteryPaused = false, safeExitRunning = false))
        assertTrue(shouldHoldWifiLock(wifiLockEnabled = true, lowBatteryPaused = false, safeExitRunning = false))
        assertFalse(shouldHoldWifiLock(wifiLockEnabled = true, lowBatteryPaused = true, safeExitRunning = false))
        assertFalse(shouldHoldWifiLock(wifiLockEnabled = true, lowBatteryPaused = false, safeExitRunning = true))
    }

    @Test
    fun `permissionSummary reports keep-alive count and external storage state`() {
        assertEquals(
            "后台保活 0/2 · 外部存储未授权",
            permissionSummary(PermissionState())
        )
        assertEquals(
            "后台保活 1/2 · 外部存储已授权",
            permissionSummary(
                PermissionState(
                    storageGranted = true,
                    notificationGranted = true,
                    batteryOptimizationIgnored = false
                )
            )
        )
        assertEquals(
            "后台保活 2/2 · 外部存储未授权",
            permissionSummary(
                PermissionState(
                    storageGranted = false,
                    notificationGranted = true,
                    batteryOptimizationIgnored = true
                )
            )
        )
    }

    @Test
    fun `two pane backup detail toggles back to empty and switches cleanly`() {
        assertEquals(
            BackupSettingsDetail,
            resolveSettingsDetailSelection(currentDetail = null, targetDetail = BackupSettingsDetail, useTwoPane = true)
        )
        assertNull(
            resolveSettingsDetailSelection(currentDetail = BackupSettingsDetail, targetDetail = BackupSettingsDetail, useTwoPane = true)
        )
        assertEquals(
            BackupSettingsDetail,
            resolveSettingsDetailSelection(currentDetail = MirrorSettingsDetail, targetDetail = BackupSettingsDetail, useTwoPane = true)
        )
        assertEquals(
            BackupSettingsDetail,
            resolveSettingsDetailSelection(currentDetail = BackupSettingsDetail, targetDetail = BackupSettingsDetail, useTwoPane = false)
        )
    }

    @Test
    fun `backupSettingsSummaryText formats disabled default custom running and error states`() {
        assertEquals(
            "未开启 · $DEFAULT_EXTERNAL_DISPLAY_PATH",
            backupSettingsSummaryText(BackupState())
        )
        assertEquals(
            "每天 04:00 · $DEFAULT_EXTERNAL_DISPLAY_PATH",
            backupSettingsSummaryText(BackupState(config = BackupConfig(autoEnabled = true)))
        )
        assertEquals(
            "每天 04:00 · 内部存储/Backups · 上次失败",
            backupSettingsSummaryText(
                BackupState(
                    config = BackupConfig(
                        autoEnabled = true,
                        customTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
                        customTreeLabel = "内部存储/Backups"
                    ),
                    lastError = "权限已失效"
                )
            )
        )
        assertEquals(
            "正在备份...",
            backupSettingsSummaryText(BackupState(running = true))
        )
    }
}
