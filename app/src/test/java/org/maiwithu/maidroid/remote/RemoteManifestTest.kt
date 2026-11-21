package org.maiwithu.maidroid.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.failureOf
import org.maiwithu.maidroid.diagnostics.updateRemoteFailureHints

class RemoteManifestTest {

    private val baseEnv = ClientEnvironment(
        appVersion = "0.2.4",
        manufacturer = "HONOR",
        sdkInt = 31,
        activeQqClient = "snowluma",
        setupComplete = true,
        nowEpochSeconds = 1795000000L
    )

    @Test
    fun announcementFilterChecksVersionBrandClientAndExpiry() {
        val targeted = RemoteAnnouncement(
            id = "ann-1",
            level = AnnouncementLevel.Warning,
            title = "协议端提醒",
            body = "说明正文",
            expiresAt = 1795003600L,
            filter = AnnouncementFilter(
                minAppVersion = "0.2.2",
                maxAppVersion = "0.2.4",
                manufacturers = listOf("honor", "huawei"),
                minSdkInt = 31,
                activeQqClients = listOf("SnowLuma")
            )
        )
        assertTrue(targeted.matches(baseEnv))
        assertFalse(targeted.matches(baseEnv.copy(appVersion = "0.2.5")))
        assertFalse(targeted.matches(baseEnv.copy(manufacturer = "Xiaomi")))
        assertFalse(targeted.matches(baseEnv.copy(activeQqClient = "napcat")))
        assertFalse(targeted.matches(baseEnv.copy(nowEpochSeconds = 1795003600L)))
    }

    @Test
    fun oobeOnlyAnnouncementSkipsCompletedDevices() {
        val oobeAnn = RemoteAnnouncement(
            id = "ann-oobe",
            title = "引导提示",
            body = "首次安装提示",
            filter = AnnouncementFilter(onlyDuringOobe = true)
        )
        assertFalse(oobeAnn.matches(baseEnv.copy(setupComplete = true)))
        assertTrue(oobeAnn.matches(baseEnv.copy(setupComplete = false)))
    }

    @Test
    fun remoteFailureHintsAttachOverrideWithoutAlteringErrorCode() {
        try {
            updateRemoteFailureHints(
                listOf(
                    RemoteFailureHint(
                        id = "h1",
                        steps = listOf("SL-INSTALL"),
                        containsAny = listOf("protocol_rejected_v9"),
                        title = "QQ 版本校验已升级",
                        advice = "请在消息平台配置中更新至最新版发行包。",
                        action = "open:app_update"
                    )
                )
            )
            val failure = failureOf(
                FailureStep.SnowLumaInstall,
                "安装失败",
                "403 Forbidden: protocol_rejected_v9"
            )
            assertEquals("SL-INSTALL/NET-HTTP", failure.code)
            assertNotNull(failure.remoteHint)
            assertEquals("QQ 版本校验已升级", failure.remoteHint?.title)
            assertNull(
                failureOf(FailureStep.AptInstall, "失败", "protocol_rejected_v9").remoteHint
            )
        } finally {
            updateRemoteFailureHints(emptyList())
        }
    }

    @Test
    fun remoteFailureHintsMatchZeroOutputExitCodeViaSummaryFallback() {
        try {
            updateRemoteFailureHints(
                listOf(
                    RemoteFailureHint(
                        id = "h-proot-182",
                        steps = listOf("CT-STORAGE", "CT-PROOT"),
                        containsAny = listOf("exit code: 182", "命令退出码: 182"),
                        title = "暂不支持鸿蒙卓易通容器",
                        advice = "proot 加载器退出码 182，请在原生安卓设备运行。"
                    )
                )
            )
            val failure = failureOf(FailureStep.ContainerStorage, "命令退出码: 182", output = "")
            assertEquals("CT-STORAGE/PROOT-182", failure.code)
            assertEquals("暂不支持鸿蒙卓易通容器", failure.remoteHint?.title)
        } finally {
            updateRemoteFailureHints(emptyList())
        }
    }
}
