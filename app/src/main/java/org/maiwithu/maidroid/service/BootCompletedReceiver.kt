package org.maiwithu.maidroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.maiwithu.maidroid.appContainer

/**
 * 开机广播：OOBE 已完成且开机自启没关时，拉起 MaiBotRuntimeService。
 *
 * Android 8+ 要求应用安装后被用户手动打开过一次，这个广播才会送达。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val settings = context.appContainer.settings
        if (!settings.autoStartOnBoot) {
            Log.d(TAG, "Boot completed, but service auto-start is disabled")
            return
        }

        if (!settings.setupComplete) {
            Log.d(TAG, "Boot completed, but OOBE setup is not complete")
            return
        }

        Log.d(TAG, "Boot completed — starting MaiBotRuntimeService")
        MaiBotRuntimeService.start(context)
    }
}
