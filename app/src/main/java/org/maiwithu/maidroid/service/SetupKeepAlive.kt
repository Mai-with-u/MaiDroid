package org.maiwithu.maidroid.service

import android.content.Context
import android.util.Log

/**
 * 首次引导装东西期间，让前台服务撑住 MaiDroid 的进程。
 *
 * 解包容器、apt、克隆源码、uv 装依赖前后要十几分钟，用户多半会切走去干别的；没有前台服务时，
 * 切到后台、在最近任务里划掉 MaiDroid，系统都会结束进程，装到一半的东西全停（以前用户反馈最多的就是这个）。
 * 有了前台服务（常驻通知 + 部分唤醒锁），切走、划卡、锁屏都照样装完（2026-11 荣耀调试机实测划卡不杀进程）。
 *
 * OobeSetupManager 每一步开工时 hold（得在应用还在前台时调，Android 12 起后台不让开前台服务，开不了就算了），
 * 这一步结束、也没有在等自动重试时 release。只为首次引导开的服务不会拉起 MaiBot、NapCat、sshd；
 * 装完最后一步正常拉起麦麦时服务转为常驻，release 就不再停它。
 */
internal object SetupKeepAlive {
    private const val TAG = "SetupKeepAlive"

    /** 首次引导现在要不要服务撑着；服务收到开工请求时据此判断是不是已经晚了（期间已经 release 过）。 */
    @Volatile
    var wanted = false
        private set

    fun hold(context: Context) {
        if (wanted) return
        wanted = true
        runCatching { MaiBotRuntimeService.start(context, MaiBotRuntimeService.ACTION_SETUP_HOLD) }
            .onFailure { error ->
                // 已经在后台（自动重试时）或系统不让开：照常装，只是没有服务撑着
                Log.w(TAG, "Cannot hold the foreground service for setup", error)
                wanted = false
            }
    }

    fun release() {
        if (!wanted) return
        wanted = false
        MaiBotRuntimeService.onSetupReleased()
    }
}
