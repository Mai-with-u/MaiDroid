package org.maiwithu.maidroid.webui

import android.os.SystemClock

/**
 * 渲染/内容进程没了之后要不要自动重新打开网页：两分钟内已经自动重开过 MAX_AUTO_REOPENS 次就不再自动开，
 * 改为让用户点“重试”，免得网页一加载就把进程弄崩时陷进“崩溃 -> 重开 -> 又崩溃”的死循环。
 * 主页 WebUI 页签和全屏页各用一份（同一次崩溃两边都会收到回调，共用一份会被算成两次）。
 */
internal class WebUiRenderCrashGuard {
    private companion object {
        const val WINDOW_MS = 2 * 60_000L
        const val MAX_AUTO_REOPENS = 3
    }

    private val recent = ArrayDeque<Long>()

    @Synchronized
    fun shouldAutoReopen(now: Long = SystemClock.elapsedRealtime()): Boolean {
        // 先剔除时间窗之外的旧崩溃记录，再判断是否超出自动重开上限。
        while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) recent.removeFirst()
        if (recent.size >= MAX_AUTO_REOPENS) return false
        recent.addLast(now)
        return true
    }
}
