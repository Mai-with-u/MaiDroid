package org.maiwithu.maidroid.oobe

import org.maiwithu.maidroid.diagnostics.FailureCause

/** 首次引导一步失败后怎么自动重试，见 oobeAutoRetry。 */
internal sealed interface OobeAutoRetry {
    /** 页脚失败说明后面补的一句。 */
    val note: String

    /** 断网了：等手机重新连上网，再等 settleMs 让网络就绪后重试。 */
    data class AfterReconnect(val settleMs: Long) : OobeAutoRetry {
        override val note: String get() = "连上网后会自动重试"
    }

    /** 其他网络原因：等 delayMs 后重试。 */
    data class AfterDelay(val delayMs: Long) : OobeAutoRetry {
        override val note: String get() = "${delayMs / 1000} 秒后自动重试"
    }
}

// 网络刚恢复时 DNS、路由往往还没就绪，等几秒再重试
private const val NETWORK_SETTLE_MS = 3_000L

/** 其他网络原因自动重试的间隔，用完就只能手动重试。 */
internal val OOBE_NETWORK_RETRY_DELAYS_MS = listOf(30_000L, 90_000L)

/**
 * 一步失败后要不要自动重试，不重试返回 null：
 * 断网 -> 等网络恢复后重试，不限次数（每次都要先等到网，不会空转）；
 * 其他网络原因（解析失败、超时、连接中断、镜像报错、TLS）-> 按 OOBE_NETWORK_RETRY_DELAYS_MS 隔一会儿重试，
 * networkRetriesSoFar 是这一轮已经这样重试过几次，用完为止；
 * 其余原因（空间满、手机时间不对、依赖冲突、认不出原因……）重试也一样失败，等用户处理完自己点按钮。
 */
internal fun oobeAutoRetry(cause: FailureCause?, networkRetriesSoFar: Int): OobeAutoRetry? = when {
    cause == FailureCause.NoNetwork -> OobeAutoRetry.AfterReconnect(NETWORK_SETTLE_MS)
    cause?.isNetwork == true -> OOBE_NETWORK_RETRY_DELAYS_MS.getOrNull(networkRetriesSoFar)?.let(OobeAutoRetry::AfterDelay)
    else -> null
}
