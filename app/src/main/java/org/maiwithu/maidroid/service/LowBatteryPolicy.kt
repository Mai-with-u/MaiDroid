package org.maiwithu.maidroid.service

/**
 * 低电量暂停的判断规则（由 MaiBotRuntimeService 每次收到电量变化时调用）。
 *
 * 电量低于 PAUSE_BELOW_PERCENT、又没插着电时，先请麦麦和 QQ 正常退出、保存好数据：
 * 手机没电关机时系统只给几秒，麦麦来不及保存，内存里的记忆和聊天就丢了。
 * 插上电，或者电量回到 RESUME_AT_PERCENT 以上，就自动拉起来。
 * 两个阈值隔开一截，电量在 5% 上下来回跳时不会反复停、启。
 */
internal object LowBatteryPolicy {
    const val PAUSE_BELOW_PERCENT = 5
    const val RESUME_AT_PERCENT = 10

    /**
     * 现在该不该暂停。paused 是现在是否已经暂停着；percent 读不到（null）时保持现状。
     * 插着电就不暂停：哪怕充得比用得慢，到 0% 时系统关机前还会发关机广播，届时再请它们退出。
     */
    fun shouldPause(paused: Boolean, percent: Int?, pluggedIn: Boolean): Boolean = when {
        percent == null -> paused
        pluggedIn -> false
        paused -> percent < RESUME_AT_PERCENT
        else -> percent < PAUSE_BELOW_PERCENT
    }

    /** 电量广播里的 level / scale 换算成百分比；任一项缺失（-1）或 scale 不合法时返回 null。 */
    fun percentOf(level: Int, scale: Int): Int? =
        if (level < 0 || scale <= 0) null else level * 100 / scale
}
