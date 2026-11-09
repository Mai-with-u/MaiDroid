package org.maiwithu.maidroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.maiwithu.maidroid.appDependencies

/**
 * 开机 / 应用升级广播：OOBE 已完成、自动恢复没关、用户上次没有安全退出时，拉起 MaiBotRuntimeService。
 *
 * Android 8+ 要求应用安装后被用户手动打开过一次，开机广播才会送达；升级广播（MY_PACKAGE_REPLACED）
 * 只在本应用被覆盖安装后发给本应用，不用任何权限。7x24 使用里升级不该让麦麦断线等用户手动打开。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package replaced"
            else -> return
        }

        val settings = context.appDependencies.settings
        if (!settings.autoStartOnBoot) {
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
        MaiBotRuntimeService.start(context)
    }
}
