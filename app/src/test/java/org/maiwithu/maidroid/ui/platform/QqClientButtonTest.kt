package org.maiwithu.maidroid.ui.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maiwithu.maidroid.platform.napcat.NapCatPlatformState
import org.maiwithu.maidroid.platform.qq.QqClient
import org.maiwithu.maidroid.platform.qqofficial.QqOfficialPlatformState
import org.maiwithu.maidroid.platform.snowluma.SnowLumaPlatformState

class QqClientButtonTest {
    private val napCatInstalled = NapCatPlatformState.initial(installed = true, qrCodePath = null, qrCodeLastModified = 0L)
    private val napCatMissing = NapCatPlatformState.initial(installed = false, qrCodePath = null, qrCodeLastModified = 0L)
    private val snowLumaMissing = SnowLumaPlatformState.initial(installed = false, active = false)
    private val snowLumaActive = SnowLumaPlatformState.initial(installed = true, active = true)

    // 点一下按钮，记下走到了哪个回调
    private fun click(method: AccessMethod, napCat: NapCatPlatformState, snowLuma: SnowLumaPlatformState): String {
        var result = ""
        val targets = PlatformInstallTargets(
            napCatState = napCat,
            snowLumaState = snowLuma,
            qqOfficialState = QqOfficialPlatformState(),
            onNapCatInstall = { result = "napcat-install" },
            onNapCatSettings = { result = "napcat-settings" },
            onSnowLumaInstall = { result = "snowluma-agreement" },
            onSnowLumaSettings = { result = "snowluma-settings" },
            onSwitchQqClient = { result = "switch-${it.wireValue}" },
            onQqOfficialSettings = { result = "official" },
            onUnsupportedInstall = { result = "unsupported" }
        )
        platformInstallAction(method, targets).onClick()
        return result
    }

    @Test
    fun `snowluma asks for the agreement first and opens settings once installed and active`() {
        assertEquals("snowluma-agreement", click(AccessMethod.SnowLumaClient, napCatInstalled, snowLumaMissing))
        assertEquals("snowluma-settings", click(AccessMethod.SnowLumaClient, napCatInstalled.copy(active = false), snowLumaActive))
    }

    @Test
    fun `picking the client that is not active asks to switch first`() {
        // 装好了 SnowLuma 但现在用 NapCat：点 SnowLuma 先确认切换
        assertEquals("switch-snowluma", click(AccessMethod.SnowLumaClient, napCatInstalled, snowLumaActive.copy(active = false)))
        // 现在用 SnowLuma：点 NapCat（装没装都一样）先确认切换
        assertEquals("switch-napcat", click(AccessMethod.NapCat, napCatInstalled.copy(active = false), snowLumaActive))
        assertEquals("switch-napcat", click(AccessMethod.NapCat, napCatMissing.copy(active = false), snowLumaActive))
        // SnowLuma 没装好（比如装到一半失败）时 NapCat 照常
        assertEquals("napcat-settings", click(AccessMethod.NapCat, napCatInstalled, snowLumaMissing))
        assertEquals("napcat-install", click(AccessMethod.NapCat, napCatMissing, snowLumaMissing))
    }

    @Test
    fun `inactive clients stay listed as stopped and are not counted as running`() {
        val cards = installedPlatformCards(
            napCatInstalled.copy(active = false),
            snowLumaActive.copy(oneBotReachable = true, accountId = "123456"),
            QqOfficialPlatformState()
        )

        val napCat = cards.first { it.kind == InstalledPlatformKind.NapCat }
        val snowLuma = cards.first { it.kind == InstalledPlatformKind.SnowLuma }
        assertEquals("NapCat 已停用", napCat.account)
        assertFalse(napCat.running)
        assertEquals("123456 在线", snowLuma.account)
        assertTrue(snowLuma.running)
        assertNull(installedPlatformCards(napCatMissing, snowLumaMissing, QqOfficialPlatformState()).firstOrNull())
    }
}
