package org.maiwithu.maidroid

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tencent.mmkv.MMKV

class ChatbotApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_SERVICE = "maidroid_service"
        lateinit var instance: ChatbotApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize MMKV
        val rootDir = MMKV.initialize(this)
        android.util.Log.d("MaiDroid", "MMKV root: $rootDir")

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
