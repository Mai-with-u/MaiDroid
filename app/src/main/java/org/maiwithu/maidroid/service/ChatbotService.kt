package org.maiwithu.maidroid.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.maiwithu.maidroid.ChatbotApplication
import org.maiwithu.maidroid.MainActivity
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.platform.NapCatRuntime
import org.maiwithu.maidroid.process.ProcessManager

/**
 * Foreground service that keeps the Python chatbot process alive.
 *
 * This service:
 * - Holds a partial WakeLock to prevent CPU sleep
 * - Displays a persistent notification so Android doesn't kill it
 * - Manages the ProcessManager lifecycle
 * - Can be started by the app or restored after boot when setup is complete
 *
 * The Python process runs inside the Termux/Debian proot container
 * and communicates with the Android UI via Unix Domain Socket.
 */
class ChatbotService : Service() {

    companion object {
        private const val TAG = "ChatbotService"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_RESTART_RUNTIME = "org.maiwithu.maidroid.action.RESTART_RUNTIME"
    }

    private lateinit var processManager: ProcessManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        processManager = ProcessManager(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")

        // Start foreground with persistent notification
        startForeground(NOTIFICATION_ID, buildNotification())

        NapCatRuntime.start(this)
        if (intent?.action == ACTION_RESTART_RUNTIME) {
            processManager.restart()
        } else {
            processManager.start()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        NapCatRuntime.stop(this)
        processManager.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * Returns the ProcessManager instance for other components to observe.
     */
    fun getProcessManager(): ProcessManager = processManager

    // ── Notification ─────────────────────────────────────────────────

    private fun buildNotification() = NotificationCompat.Builder(
        this,
        ChatbotApplication.NOTIFICATION_CHANNEL_SERVICE
    )
        .setContentTitle(getString(R.string.notification_running))
        .setContentText(getString(R.string.notification_tap_open))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(createMainActivityIntent())
        .build()

    private fun createMainActivityIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    // ── WakeLock ─────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MaiDroid:ChatbotService"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }
}
