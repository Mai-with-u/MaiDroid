package org.maiwithu.maidroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import org.maiwithu.maidroid.MainActivity
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.appDependencies

/**
 * 托管容器里所有长驻进程的前台服务：MaiBot、NapCat（装了才启动）和容器 sshd（开了才启动）。
 *
 * 常驻通知 + 部分唤醒锁让系统不在后台杀掉它；服务销毁时依次停掉 sshd、NapCat、MaiBot。
 * 所有“把 MaiBot 拉起来 / 重启”的入口（主页唤醒、开机广播、装完平台、在线更新）都经 start 走到这里。
 */
class MaiBotRuntimeService : Service() {

    companion object {
        private const val TAG = "MaiBotRuntimeService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "maidroid_service"

        /** 重启 MaiBot（以及没在跑的 NapCat），用于装好或改了平台适配器之后让 MaiBot 重新加载插件。 */
        const val ACTION_RESTART_RUNTIME = "org.maiwithu.maidroid.action.RESTART_RUNTIME"

        /** 只按设置启停容器 sshd，不碰 MaiBot 和 NapCat。 */
        const val ACTION_SYNC_CONTAINER_SSH = "org.maiwithu.maidroid.action.SYNC_CONTAINER_SSH"

        /**
         * 拉起（或唤醒已在运行的）前台服务。
         * 不带 action 时启动 NapCat 和 MaiBot（已在运行的不会重复启动）；action 取值见上面两个常量。
         * minSdk 为 26，后台拉起服务只能走前台服务入口，服务会在 onStartCommand 里立刻挂上常驻通知。
         */
        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, MaiBotRuntimeService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        /** 应用启动时注册常驻通知用的渠道（重要性低，不响铃不弹出）。 */
        fun createNotificationChannel(context: Context) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private val maiBotProcess get() = appDependencies.maiBotProcess
    private val containerSsh get() = appDependencies.containerSsh
    private val napCatProcess get() = appDependencies.napCatProcess
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        acquireWakeLock()
    }

    // 每次 start 都会走到这里：先挂常驻通知 -> 按设置同步 sshd -> 只同步 SSH 时到此为止；
    // 否则拉起 NapCat，再按 action 启动或重启 MaiBot。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification())

        containerSsh.syncWithSettings()
        if (intent?.action == ACTION_SYNC_CONTAINER_SSH) {
            return START_STICKY
        }

        napCatProcess.start()
        if (intent?.action == ACTION_RESTART_RUNTIME) {
            maiBotProcess.restart()
        } else {
            maiBotProcess.start()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        containerSsh.stop()
        napCatProcess.stop()
        maiBotProcess.stop()
        releaseWakeLock()
        super.onDestroy()
    }

    // 点通知回到主界面；singleTop 避免叠出第二个主界面。
    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
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

    // 部分唤醒锁只保持 CPU 运行，屏幕照常熄灭；不计数，重复 acquire 也只需释放一次。
    private fun acquireWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MaiDroid:MaiBotRuntimeService"
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
