package org.maiwithu.maidroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maiwithu.maidroid.MainActivity
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.appDependencies
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.maibot.MaiBotStartup
import org.maiwithu.maidroid.maibot.MaiBotStartupResult
import org.maiwithu.maidroid.maibot.failure
import org.maiwithu.maidroid.maibot.maiBotRecentRunOutput
import org.maiwithu.maidroid.maibot.shouldReportBackgroundMaiBotCrash
import org.maiwithu.maidroid.runtime.ContainerDns
import org.maiwithu.maidroid.terminal.AnsiText
import org.maiwithu.maidroid.terminal.TerminalLog
import java.util.TimeZone

/**
 * 托管容器里所有长驻进程的前台服务：MaiBot、QQ 协议端（NapCat 或 SnowLuma，装了、启用了才启动）和容器 sshd（开了才启动）。
 *
 * 常驻通知 + 部分唤醒锁让系统不在后台杀掉它；服务销毁时依次停掉 sshd、QQ 协议端、MaiBot。
 * 所有“把 MaiBot 拉起来 / 重启”的入口（主页唤醒、开机广播、装完平台、在线更新）都经 start 走到这里。
 * 设置页的“安全退出”也由它执行（ACTION_SAFE_EXIT，流程见 SafeExitManager）：等所有服务正常退出后停掉自己并关闭 MaiDroid。
 * 服务活着时还监听手机改时区，让容器时区跟着改（见 ContainerTimeZone）。
 * 还盯着电量：电量低、又没插电时先让麦麦和 QQ 保存好数据停下，插上电再拉起来（规则见 LowBatteryPolicy）；
 * 手机关机前收到关机广播时，抢那几秒请它们正常退出。
 * 首次引导装东西期间也由它撑住进程（ACTION_SETUP_HOLD，见 SetupKeepAlive）：只挂通知和唤醒锁，不拉起任何容器进程。
 */
class MaiBotRuntimeService : Service() {

    companion object {
        private const val TAG = "MaiBotRuntimeService"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_ID = "maidroid_service"

        // 网络变化后等这么久再刷新容器 DNS：切换网络时回调会连着来好几次
        private const val NETWORK_SETTLE_MS = 2_000L

        // 关机广播最多等所有接收者 10 秒，留两秒余量：到点还没停完就放行关机，剩下的交给重新打开后的恢复
        private const val SHUTDOWN_WAIT_MS = 8_000L

        // 常驻后台期间每小时拉取一次远程公告与排障热规则，照顾长期挂后台不打开界面的旧手机
        private const val REMOTE_OPS_POLL_MS = 60 * 60 * 1000L

        /** 重启 MaiBot（以及没在跑的 QQ 协议端），用于装好或改了平台适配器之后让 MaiBot 重新加载插件。 */
        const val ACTION_RESTART_RUNTIME = "org.maiwithu.maidroid.action.RESTART_RUNTIME"

        /** 只按设置启停容器 sshd，不碰 MaiBot 和 QQ 协议端。 */
        const val ACTION_SYNC_CONTAINER_SSH = "org.maiwithu.maidroid.action.SYNC_CONTAINER_SSH"

        /** 安全退出：让所有长驻进程正常退出，停完后关闭 MaiDroid。只由 SafeExitManager.request 发出。 */
        const val ACTION_SAFE_EXIT = "org.maiwithu.maidroid.action.SAFE_EXIT"

        /** 首次引导装东西期间撑住进程，不拉起任何容器进程。只由 SetupKeepAlive.hold 发出。 */
        const val ACTION_SETUP_HOLD = "org.maiwithu.maidroid.action.SETUP_HOLD"

        // 活着的服务实例，SetupKeepAlive.release 经它在主线程停掉只为首次引导开的服务（同一进程内调用，不经系统）
        @Volatile
        private var instance: MaiBotRuntimeService? = null

        /** 首次引导不再需要服务撑着：服务只是为它开的就停掉，已经在托管麦麦就什么也不做。 */
        fun onSetupReleased() {
            Handler(Looper.getMainLooper()).post { instance?.stopIfOnlyHoldingSetup() }
        }

        /**
         * 拉起（或唤醒已在运行的）前台服务。
         * 不带 action 时启动已启用的 QQ 协议端和 MaiBot（已在运行的不会重复启动）；action 取值见上面几个常量。
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
    private val snowLumaProcess get() = appDependencies.snowLumaProcess
    private val safeExit get() = appDependencies.safeExit
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // 安全退出用：执行退出、刷新通知的协程跑在主线程，服务销毁时取消。
    private val serviceScope = backgroundScope("前台服务", Dispatchers.Main.immediate)
    private var safeExitRunning = false

    // 安全退出把服务都停完、服务停掉自己之后，在 onDestroy 里关闭 MaiDroid。
    private var closeAppOnDestroy = false

    // 收到过拉起 MaiBot、QQ 协议端、sshd 的请求（不只是首次引导的 ACTION_SETUP_HOLD）；为 false 时停服务不用去停它们。
    private var runtimeRequested = false

    // 电量低暂停着（见 onBatteryChanged）；只在主线程读写。lowBatteryPauseJob 是这次暂停“请它们退出”的那一轮，
    // 恢复时要等它做完再拉起，免得 QQ 还没退完就被当成在跑、没拉起来。
    private var lowBatteryPaused = false
    private var lowBatteryPauseJob: Job? = null

    // 电量、充电状态一变系统就发这条（粘性广播，注册时立刻收到一次当前状态）。
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) onBatteryChanged(intent)
        }
    }

    // 手机关机、重启（包括没电自动关机）前系统发这条，只发给运行中注册的接收者，最多等 10 秒：
    // 拖住广播 -> 麦麦和 QQ 同时请它们保存好数据退出 -> 都停下或到 SHUTDOWN_WAIT_MS 就放行关机。
    // 只停不拦：万一没真关机，之后的唤醒照样能把它们拉起来；下次开机由开机广播照常自启。
    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN) return
            Log.d(TAG, "Phone is shutting down")
            TerminalLog.append("[MaiDroid] 手机正在关机：抓紧请麦麦和 QQ 保存好数据退出")
            val pending = goAsync()
            serviceScope.launch {
                try {
                    withTimeoutOrNull(SHUTDOWN_WAIT_MS) {
                        withContext(Dispatchers.IO) {
                            supervisorScope {
                                launch { maiBotProcess.stopAndWait() }
                                launch { napCatProcess.stopAndWait() }
                                launch { snowLumaProcess.stopAndWait() }
                            }
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }

    // 手机改时区（手动改，或开着自动时区跨了时区）时系统发这条广播：同步容器时区，MaiBot 在跑就重启。
    private val timeZoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
            Log.d(TAG, "Phone time zone changed")
            // 框架收到改时区时也会这样清掉本进程缓存的默认时区；这里先清一次，免得广播比框架的更新先到、读到旧时区。
            TimeZone.setDefault(null)
            maiBotProcess.followPhoneTimeZone()
        }
    }

    // 时区广播只在服务活着（容器在跑）时需要跟进，所以随服务注册、随服务注销；它是系统广播，不对其他应用开放。
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        instance = this
        acquireKeepAliveLocks()
        ContextCompat.registerReceiver(
            this,
            timeZoneReceiver,
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            this,
            shutdownReceiver,
            IntentFilter(Intent.ACTION_SHUTDOWN),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // 电量广播是粘性的，注册时就返回当前电量：服务一起来就先按它判断，
        // 开机自启、打开应用时电量已经很低，onStartCommand 里的启动请求就会被拦下，不会拉起来又马上停掉。
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )?.let(::onBatteryChanged)
        // 手机换了网络（Wi-Fi 和移动数据切换、断网后恢复）：等网络稳定两秒，按新网络重写容器的 DNS，
        // 跑着的麦麦和 QQ 协议端下次解析域名就用上新的（glibc 发现 resolv.conf 变了会重读），不用重启它们
        serviceScope.launch {
            appDependencies.networkMonitor.changes.collectLatest {
                delay(NETWORK_SETTLE_MS)
                if (runtimeRequested) refreshContainerDns()
            }
        }
        serviceScope.launch {
            while (true) {
                delay(REMOTE_OPS_POLL_MS)
                if (runtimeRequested && !lowBatteryPaused && !safeExitRunning) {
                    appDependencies.remoteOps.refreshFromBackgroundService()
                    appDependencies.remoteOps.reportDailyHeartbeat()
                }
            }
        }
        // 服务刚起或开机自启时先补发一次上次尚未上报的崩溃记录
        appDependencies.remoteOps.reportPendingCrashIfNeeded()
        // 监听后台 MaiBot 启动阶段异常（例如开机自启无人盯盘时连续退出），提取纯 Python Traceback 上报；
        // 改看稳定运行后会归零的 consecutiveExits，避免长跑偶发被系统 SIGKILL 强杀累计 2 次后误报 MB-CRASH。
        serviceScope.launch {
            var lastReportedConsecutiveExits = 0
            var lastReportedBlockedReason: String? = null
            maiBotProcess.status.collect { status ->
                if (!runtimeRequested || lowBatteryPaused || safeExitRunning) return@collect
                val blocked = status.launchBlockedReason
                if (blocked != null && blocked != lastReportedBlockedReason) {
                    lastReportedBlockedReason = blocked
                    MaiBotStartupResult.Blocked(blocked).failure("")?.let { failure ->
                        appDependencies.remoteOps.reportMaiBotStartupFailure(failure)
                    }
                } else if (blocked == null) {
                    lastReportedBlockedReason = null
                }
                if (status.consecutiveExits < MaiBotStartup.MAX_EXITS) {
                    lastReportedConsecutiveExits = 0
                } else if (shouldReportBackgroundMaiBotCrash(status.consecutiveExits, lastReportedConsecutiveExits)) {
                    lastReportedConsecutiveExits = status.consecutiveExits
                    val recentOut = maiBotRecentRunOutput(TerminalLog.logs.value.map(AnsiText::plain))
                    MaiBotStartupResult.Crashed(status.consecutiveExits, status.lastExitCode).failure(recentOut)?.let { failure ->
                        appDependencies.remoteOps.reportMaiBotStartupFailure(failure)
                    }
                }
            }
        }
    }

    // 按手机现在的网络重写容器的 resolv.conf，改了才记一行；rootfs 还没装好、写不进去时什么也不做
    private suspend fun refreshContainerDns() {
        val changed = withContext(Dispatchers.IO) {
            ContainerDns.refresh(applicationContext, appDependencies.runtimePaths.rootfsDir)
        }
        if (changed) TerminalLog.append("[MaiDroid] 已按手机现在的网络更新容器的 DNS 设置")
    }

    // 每次 start 都会走到这里：先挂常驻通知 -> 按设置同步 sshd -> 只同步 SSH 时到此为止；
    // 否则拉起 QQ 协议端（NapCat、SnowLuma 只有被启用的那个会起来），再按 action 启动或重启 MaiBot。
    // 安全退出开始后（包括退出途中收到唤醒、更新完成等其他启动请求）只接着退出，什么也不拉起。
    // 用户退出后、进程却在退出途中被系统杀掉时，系统重建 START_STICKY 服务会传 null，这时也只接着退出。
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action}")
        val settings = appDependencies.settings
        if (intent?.action == ACTION_SAFE_EXIT || safeExit.started || (intent == null && settings.userExited)) {
            promoteToForeground(buildSafeExitNotification(safeExit.state.value))
            runSafeExit()
            // 退出途中进程若被杀，不要让系统重建服务把麦麦拉起来。
            return START_NOT_STICKY
        }

        // 首次引导装东西期间撑住进程：挂上“正在配置”的通知，不拉起任何容器进程（已经在托管麦麦时照旧用运行中的通知）；
        // 请求送到之前首次引导已经 release 了（这一步很快就结束了），挂完通知立刻停掉。
        if (intent?.action == ACTION_SETUP_HOLD) {
            promoteToForeground(if (runtimeRequested) buildNotification() else buildSetupNotification())
            if (!SetupKeepAlive.wanted) stopIfOnlyHoldingSetup()
            return if (runtimeRequested) START_STICKY else START_NOT_STICKY
        }

        // 其余启动请求都来自应用里（打开主页、唤醒、装平台、开 SSH、更新结束）或开机自启：用户又在用了，不再算“已退出”。
        settings.userExited = false
        runtimeRequested = true
        promoteToForeground(buildNotification())

        containerSsh.syncWithSettings()
        if (intent?.action == ACTION_SYNC_CONTAINER_SSH) {
            return START_STICKY
        }

        // 拉起之前先按现在的网络写好容器的 DNS：首次引导时的网络可能早就换了，文件也可能被断电写空了
        serviceScope.launch { refreshContainerDns() }

        napCatProcess.start()
        snowLumaProcess.start()
        if (intent?.action == ACTION_RESTART_RUNTIME) {
            maiBotProcess.restart()
        } else {
            maiBotProcess.start()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Android 14+ 若系统认为当前前台服务类型触发了运行时限，收到回调后主动停止服务避免触发系统强杀与异常。
    override fun onTimeout(startId: Int) {
        handleForegroundTimeout(startId, fgsType = null)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleForegroundTimeout(startId, fgsType = fgsType)
    }

    private fun handleForegroundTimeout(startId: Int, fgsType: Int?) {
        Log.w(TAG, "Foreground service timeout (startId=$startId, fgsType=$fgsType)")
        TerminalLog.append("[MaiDroid] 系统通知前台服务超时，安全停止前台服务避免进程被强杀")
        stopSelf(startId)
    }

    // API 34+ 显式传入 specialUse 类型以匹配清单声明，旧系统沿用两参 startForeground。
    private fun promoteToForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy")
        instance = null
        unregisterReceiver(timeZoneReceiver)
        unregisterReceiver(shutdownReceiver)
        unregisterReceiver(batteryReceiver)
        serviceScope.cancel()
        // 只为首次引导开的服务：没有拉起过任何容器进程，不用去停
        if (!runtimeRequested) {
            releaseKeepAliveLocks()
            super.onDestroy()
            return
        }
        if (closeAppOnDestroy) {
            // 安全退出已经把服务都停好了。onDestroy 返回后系统才记下服务已经停止，所以放到主线程下一轮再关闭应用。
            releaseKeepAliveLocks()
            super.onDestroy()
            Handler(Looper.getMainLooper()).post { closeMaiDroid(applicationContext) }
            return
        }
        containerSsh.stop()
        napCatProcess.stop()
        snowLumaProcess.stop()
        maiBotProcess.stop()
        releaseKeepAliveLocks()
        super.onDestroy()
    }

    /**
     * 执行安全退出（只执行一次）：退出进度变化时刷新常驻通知 -> 等所有服务停下 ->
     * 撤掉通知、停掉服务（系统不会再重建它）-> onDestroy 里关闭 MaiDroid。
     * 服务一直挂着前台通知、拿着唤醒锁，用户切走或划掉 MaiDroid 时进程也能撑到服务全部停完。
     */
    private fun runSafeExit() {
        if (safeExitRunning) return
        safeExitRunning = true
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notificationUpdates = serviceScope.launch {
            safeExit.state.collect { state ->
                if (!state.finished) notificationManager.notify(NOTIFICATION_ID, buildSafeExitNotification(state))
            }
        }
        serviceScope.launch {
            safeExit.run()
            notificationUpdates.cancel()
            closeAppOnDestroy = true
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    // 电量变化：按 LowBatteryPolicy 判断该不该暂停，和现在不一样才动手。安全退出开始后不再管，退出流程会把它们都停掉。
    private fun onBatteryChanged(intent: Intent) {
        if (safeExitRunning || safeExit.started) return
        val percent = LowBatteryPolicy.percentOf(
            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        )
        val pluggedIn = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val pause = LowBatteryPolicy.shouldPause(lowBatteryPaused, percent, pluggedIn)
        if (pause == lowBatteryPaused) return
        lowBatteryPaused = pause
        if (pause) pauseForLowBattery(percent) else resumeAfterLowBattery(pluggedIn, percent)
    }

    // 电量低：常驻通知改成说明暂停了 -> 麦麦和 QQ 同时请它们保存好数据退出（之后的启动请求都被拦下）->
    // 都停下后还没插上电就放掉保活锁，让手机能睡下省电。服务和通知留着，插上电时才能接着拉起来。
    private fun pauseForLowBattery(percent: Int?) {
        Log.d(TAG, "Battery low ($percent%), pausing MaiBot and QQ client")
        TerminalLog.append("")
        TerminalLog.append("[MaiDroid] 电量只剩 $percent%，又没在充电：先让麦麦和 QQ 保存好数据停下，插上充电后自动启动")
        refreshRuntimeNotification()
        lowBatteryPauseJob = serviceScope.launch {
            withContext(Dispatchers.IO) {
                supervisorScope {
                    launch { maiBotProcess.pauseForLowBattery() }
                    launch { napCatProcess.pauseForLowBattery() }
                    launch { snowLumaProcess.pauseForLowBattery() }
                }
            }
            if (lowBatteryPaused) releaseKeepAliveLocks()
        }
    }

    // 插上电或电量回升：拿回保活锁 -> 解除各平台拦截 -> 等暂停那一轮停完 ->
    // 服务本来在托管麦麦（不只是为首次引导开的）就像平常一样拉起已启用的 QQ 协议端和麦麦。
    private fun resumeAfterLowBattery(pluggedIn: Boolean, percent: Int?) {
        Log.d(TAG, "Battery recovered ($percent%, plugged=$pluggedIn), resuming")
        val reason = if (pluggedIn) "插上电了" else "电量回到 $percent%"
        TerminalLog.append("[MaiDroid] $reason，麦麦和 QQ 不用再停着" + if (runtimeRequested) "，重新启动它们" else "")
        acquireKeepAliveLocks()
        maiBotProcess.resumeAfterLowBattery()
        napCatProcess.resumeAfterLowBattery()
        snowLumaProcess.resumeAfterLowBattery()
        refreshRuntimeNotification()
        val pauseJob = lowBatteryPauseJob
        serviceScope.launch {
            pauseJob?.join()
            if (lowBatteryPaused || !runtimeRequested || safeExit.started) return@launch
            napCatProcess.start()
            snowLumaProcess.start()
            maiBotProcess.start()
        }
    }

    // 暂停、恢复时换常驻通知的说明；安全退出用它自己的通知，只为首次引导开的服务保持“正在安装”的通知。
    private fun refreshRuntimeNotification() {
        if (!runtimeRequested || safeExitRunning) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    // 首次引导不再需要撑着：服务只是为它开的（没在托管麦麦、没在安全退出）就撤掉通知、停掉自己。
    private fun stopIfOnlyHoldingSetup() {
        if (runtimeRequested || safeExitRunning) return
        Log.d(TAG, "Setup finished, stopping the service held for it")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // 首次引导期间的常驻通知：告诉用户切走也会接着装；点通知回到安装页看进度。
    private fun buildSetupNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_setup))
        .setContentText(getString(R.string.notification_setup_text))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(createMainActivityIntent())
        .build()

    // 点通知回到主界面；singleTop 避免叠出第二个主界面。电量低暂停着时说明暂停了、插上电会自己恢复。
    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(if (lowBatteryPaused) R.string.notification_low_battery else R.string.notification_running))
        .setContentText(getString(if (lowBatteryPaused) R.string.notification_low_battery_text else R.string.notification_tap_open))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(createMainActivityIntent())
        .build()

    // 安全退出期间的常驻通知：标题说明正在退出，正文是还没停下的第一项在做什么；进度条不定长。
    private fun buildSafeExitNotification(state: SafeExitState) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setContentTitle(getString(R.string.notification_safe_exit))
        .setContentText(safeExitNotificationText(state))
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setProgress(0, 0, true)
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

    // 同时持有 CPU 部分唤醒锁与低延迟 Wi-Fi 锁：拔电息屏后保持容器与网络活跃（防 Wi-Fi 深睡导致 QQ 断线），
    // 低电量暂停与服务销毁时再一并放开让设备节能。已经拿着就不再新建。
    private fun acquireKeepAliveLocks() {
        if (wakeLock?.isHeld != true) {
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
        acquireWifiLock()
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager.createWifiLock(mode, "MaiDroid:MaiBotRuntimeService").apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WifiLock acquired")
        }.onFailure { error ->
            Log.w(TAG, "Failed to acquire WifiLock", error)
        }
    }

    private fun releaseKeepAliveLocks() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
        releaseWifiLock()
    }

    private fun releaseWifiLock() {
        runCatching {
            wifiLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WifiLock released")
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "Failed to release WifiLock", error)
        }
        wifiLock = null
    }
}
