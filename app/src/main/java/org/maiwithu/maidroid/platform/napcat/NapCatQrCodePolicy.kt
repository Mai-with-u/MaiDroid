package org.maiwithu.maidroid.platform.napcat

// 真机实测 QQ 登录二维码出码约 120 秒后失效，QQ 有时回 ErrType 1 / ErrCode 3，有时回 ErrCode 10。
// 当前 NapCat 只在 ErrCode 3 时自己重新出码，遇到 10 就一直停在旧码上，所以要由我们按码龄主动换。
// 门限比 120 秒多留 5 秒：ErrCode 3 时让 NapCat 先自己出码，免得我们紧跟着再换一次把新码作废。
internal const val NapCatQrCodeLifetimeMs = 125_000L

// 已扫码但迟迟没在手机上确认：再多等这么久仍未登录，说明这次扫码已作废（取消或超时）。
internal const val NapCatQrScannedGraceMs = 60_000L

// 刷新失败（QQ 登录服务重连中等）时不要连着打，至少隔这么久再自动重试。
internal const val NapCatQrRetryIntervalMs = 15_000L

/**
 * 二维码弹窗开着时，每轮轮询后判断要不要替用户换新码：
 * 已登录、登录后初始化中、登录服务重连中、正在出码 -> 不动，等 NapCat 自己推进；
 * 已扫码 -> 码龄超过有效期再加确认宽限仍未登录，才视为扫码作废并换码，避免打断正在手机上确认的用户；
 * 其余（等待扫码、离线）-> NapCat 报“过期”或码龄超过有效期就换；还没有码时码龄视为无穷大，直接要一张。
 */
internal fun shouldAutoRefreshQrCode(
    status: NapCatLoginStatus,
    qrAgeMs: Long,
    sinceLastAttemptMs: Long
): Boolean {
    if (status.isLogin) return false
    if (sinceLastAttemptMs < NapCatQrRetryIntervalMs) return false
    return when (status.phase) {
        NapCatLoginPhase.Ready,
        NapCatLoginPhase.Initializing,
        NapCatLoginPhase.Reconnecting,
        NapCatLoginPhase.GeneratingQrCode -> false
        NapCatLoginPhase.QrCodeScanned -> qrAgeMs >= NapCatQrCodeLifetimeMs + NapCatQrScannedGraceMs
        NapCatLoginPhase.WaitingQrCode,
        NapCatLoginPhase.Other -> status.loginError.contains("过期") || qrAgeMs >= NapCatQrCodeLifetimeMs
    }
}
