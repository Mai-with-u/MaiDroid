package org.maiwithu.maidroid.platform.napcat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatQrCodeRefreshTest {

    // 按 NapCat 接口原样的 loginPhase 字符串构造，顺带覆盖取值解析。
    private fun status(
        phase: String,
        isLogin: Boolean = false,
        loginError: String = ""
    ) = NapCatLoginStatus(
        isLogin = isLogin,
        phase = NapCatLoginPhase.fromWireValue(phase),
        loginError = loginError
    )

    // 距上次刷新足够久，不受重试间隔限制。
    private val idle = Long.MAX_VALUE

    @Test
    fun `waiting qr code is refreshed once it outlives its lifetime`() {
        assertFalse(shouldAutoRefreshQrCode(status("waiting_qrcode"), NapCatQrCodeLifetimeMs - 1, idle))
        assertTrue(shouldAutoRefreshQrCode(status("waiting_qrcode"), NapCatQrCodeLifetimeMs, idle))
    }

    @Test
    fun `missing qr file counts as expired`() {
        // 没有 qrcode.png 时 lastModified 为 0，码龄约等于当前时间戳。
        assertTrue(shouldAutoRefreshQrCode(status("offline"), System.currentTimeMillis(), idle))
    }

    @Test
    fun `expired error from napcat triggers refresh even for a young code`() {
        assertTrue(shouldAutoRefreshQrCode(status("waiting_qrcode", loginError = "二维码已过期，请刷新"), 5_000L, idle))
    }

    @Test
    fun `scanned code gets extra time for confirmation on the phone`() {
        val scanned = status("qrcode_scanned")
        assertFalse(shouldAutoRefreshQrCode(scanned, NapCatQrCodeLifetimeMs, idle))
        assertFalse(shouldAutoRefreshQrCode(scanned, NapCatQrCodeLifetimeMs + NapCatQrScannedGraceMs - 1, idle))
        assertTrue(shouldAutoRefreshQrCode(scanned, NapCatQrCodeLifetimeMs + NapCatQrScannedGraceMs, idle))
    }

    @Test
    fun `logged in or transitional phases are left alone`() {
        val old = NapCatQrCodeLifetimeMs * 10
        assertFalse(shouldAutoRefreshQrCode(status("ready", isLogin = true), old, idle))
        assertFalse(shouldAutoRefreshQrCode(status("initializing"), old, idle))
        assertFalse(shouldAutoRefreshQrCode(status("reconnecting"), old, idle))
        assertFalse(shouldAutoRefreshQrCode(status("generating_qrcode"), old, idle))
    }

    @Test
    fun `failed refresh is not retried within the retry interval`() {
        val old = NapCatQrCodeLifetimeMs * 10
        assertFalse(shouldAutoRefreshQrCode(status("waiting_qrcode"), old, NapCatQrRetryIntervalMs - 1))
        assertTrue(shouldAutoRefreshQrCode(status("waiting_qrcode"), old, NapCatQrRetryIntervalMs))
    }
}
