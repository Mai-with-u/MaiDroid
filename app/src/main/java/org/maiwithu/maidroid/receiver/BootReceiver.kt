package org.maiwithu.maidroid.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.maiwithu.maidroid.service.ChatbotService

/**
 * Receives BOOT_COMPLETED broadcast and starts the ChatbotService.
 *
 * On Android 8+ (API 26+), the app must have been launched at least once
 * by the user after install before this receiver will fire.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed — starting ChatbotService")

            val serviceIntent = Intent(context, ChatbotService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
