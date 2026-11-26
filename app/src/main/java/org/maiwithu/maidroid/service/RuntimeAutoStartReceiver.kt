package org.maiwithu.maidroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.terminal.TerminalLog

/**
 * 开机 / 应用升级广播：OOBE 已完成、自动恢复没关、用户上次没有安全退出时，拉起 MaiBotRuntimeService。
 *
 * Android 8+ 要求应用安装后被用户手动打开过一次，开机广播才会送达；升级广播（MY_PACKAGE_REPLACED）
 * 只在本应用被覆盖安装后发给本应用，不用任何权限。7x24 使用里升级不该让麦麦断线等用户手动打开。
 */
class RuntimeAutoStartReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RuntimeAutoStartReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package replaced"
            else -> return
        }

        val settings = context.appDependencies.settings
        if (settings.backupAutoEnabled) {
            runCatching { context.appDependencies.backupManager.syncJobSchedule() }
        }
        if (!settings.autoStartService) {
            Log.d(TAG, "Received $reason broadcast, but service auto-resume is disabled")
            return
        }

        if (!settings.setupComplete) {
            Log.d(TAG, "Received $reason broadcast, but OOBE setup is not complete")
            return
        }

        // 用户上次是在设置页安全退出的：和强行停止应用后一样，不自己拉起，等用户打开 MaiDroid。
        if (settings.userExited) {
            Log.d(TAG, "Received $reason broadcast, but the user exited MaiDroid last time")
            return
        }

        Log.d(TAG, "Received $reason broadcast — starting MaiBotRuntimeService")
        runCatching { MaiBotRuntimeService.start(context) }
            .onFailure { error ->
                // 个别系统限制后台拉起前台服务时只记录日志，避免广播接收器未捕获异常崩掉进程
                Log.w(TAG, "Failed to start MaiBotRuntimeService on $reason", error)
                TerminalLog.append("[MaiDroid] 自启动前台服务失败（$reason）：${error.message ?: error.javaClass.simpleName}")
            }
    }
}
