package org.maiwithu.maidroid.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maiwithu.maidroid.BuildConfig
import org.maiwithu.maidroid.MainActivity
import org.maiwithu.maidroid.R
import org.maiwithu.maidroid.diagnostics.DiagnosticsManager
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FeedbackPackage
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.diagnostics.latestExitReasonText
import org.maiwithu.maidroid.diagnostics.updateRemoteFailureHints
import org.maiwithu.maidroid.platform.qq.QqClientChoice
import org.maiwithu.maidroid.runtime.NetworkStatus
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.writeTextAtomically
import org.maiwithu.maidroid.settings.AppSettings
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程公告、动态排障热规则、6 位故障求助码上传与无感匿名统计的进程级协调器。
 *
 * 1. 启动即从本地缓存恢复上次验签通过的清单（热规则零延迟生效），随后在打开界面及前台服务每小时后台增量刷新；
 * 2. 匹配当前设备环境的公告拆成顶部横幅列表（info/warning）与模态强提醒（blocker），开启系统栏提醒的新公告只推送一次通知；
 * 3. 匿名运行统计严格遵守用户开关与时间窗口节流，仅发结构化枚举，不含任何日志文本或个人数据。
 */
internal class RemoteOpsManager(
    context: Context,
    private val settings: AppSettings,
    private val paths: RuntimePaths,
    private val diagnostics: DiagnosticsManager
) {
    companion object {
        const val ANNOUNCEMENT_CHANNEL_ID = "maidroid_announcements"
        const val EXTRA_ANNOUNCEMENT_ID = "org.maiwithu.maidroid.extra.ANNOUNCEMENT_ID"

        private const val MIN_FOREGROUND_REFRESH_MS = 5 * 60 * 1000L
        private const val BACKGROUND_POLL_INTERVAL_MS = 60 * 60 * 1000L
        private const val DAILY_HEARTBEAT_INTERVAL_SEC = 24 * 3600L
        private const val FAILURE_REPORT_COOLDOWN_MS = 60 * 60 * 1000L
        private const val NOTIFICATION_BASE_ID = 2200

        fun createAnnouncementChannel(context: Context) {
            val channel = NotificationChannel(
                ANNOUNCEMENT_CHANNEL_ID,
                context.getString(R.string.notification_channel_announcements),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_announcements_desc)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private val appContext = context.applicationContext
    private val scope = backgroundScope("远程公告与诊断", Dispatchers.Main.immediate)
    private val refreshMutex = Mutex()
    private val client = RemoteOpsClient(userAgent = "MaiDroid/${BuildConfig.VERSION_NAME}")
    private val cacheFile = File(appContext.filesDir, "remote/manifest.json")
    private val telemetryJson = Json { encodeDefaults = false }

    private var currentManifest: RemoteManifest = RemoteManifest()
    private var lastRefreshRealtimeMs = 0L
    private val sessionDismissedIds = ConcurrentHashMap.newKeySet<String>()
    private val lastFailureReportedMs = ConcurrentHashMap<String, Long>()

    private val _bannerAnnouncements = MutableStateFlow<List<RemoteAnnouncement>>(emptyList())
    val bannerAnnouncements: StateFlow<List<RemoteAnnouncement>> = _bannerAnnouncements.asStateFlow()

    private val _blockerAnnouncement = MutableStateFlow<RemoteAnnouncement?>(null)
    val blockerAnnouncement: StateFlow<RemoteAnnouncement?> = _blockerAnnouncement.asStateFlow()

    private val _focusedAnnouncement = MutableStateFlow<RemoteAnnouncement?>(null)
    val focusedAnnouncement: StateFlow<RemoteAnnouncement?> = _focusedAnnouncement.asStateFlow()

    @Serializable
    private data class TelemetryPayload(
        val installId: String,
        val event: String,
        val appVersion: String,
        val versionCode: Int,
        val manufacturer: String,
        val model: String,
        val sdkInt: Int,
        val oobeStep: String? = null,
        val setupComplete: Boolean,
        val activeQqClient: String? = null,
        val errorCode: String? = null,
        val lastExitReason: String? = null
    )

    init {
        scope.launch {
            val restored = withContext(Dispatchers.IO) {
                val raw = runCatching { cacheFile.takeIf { it.isFile }?.readText() }.getOrNull() ?: return@withContext null
                ManifestSignatureVerifier.verifyAndParse(raw, BuildConfig.REMOTE_SIGNING_PUBLIC_KEYS)
            }
            if (restored != null) {
                applyManifest(restored, notifySystemBar = false)
            }
        }
    }

    /** 打开界面或首次引导进入时触发刷新（默认 5 分钟内不重复拉取）。 */
    fun refreshOnForeground() {
        val now = SystemClock.elapsedRealtime()
        if (lastRefreshRealtimeMs > 0L && now - lastRefreshRealtimeMs < MIN_FOREGROUND_REFRESH_MS) {
            recomputeVisibleAnnouncements()
            return
        }
        triggerRefresh()
    }

    /** 后台常驻服务每小时轮询入口。 */
    fun refreshFromBackgroundService() {
        val now = SystemClock.elapsedRealtime()
        if (lastRefreshRealtimeMs > 0L && now - lastRefreshRealtimeMs < BACKGROUND_POLL_INTERVAL_MS) return
        triggerRefresh()
    }

    private fun triggerRefresh() {
        scope.launch {
            refreshMutex.withLock {
                if (!NetworkStatus.read(appContext).connected) return@withLock
                lastRefreshRealtimeMs = SystemClock.elapsedRealtime()
                when (
                    val res = client.fetchManifest(
                        baseUrl = BuildConfig.REMOTE_OPS_BASE_URL,
                        fallbackUrl = BuildConfig.REMOTE_MANIFEST_FALLBACK_URL,
                        cachedEtag = settings.remoteManifestEtag,
                        extraKeysSpec = BuildConfig.REMOTE_SIGNING_PUBLIC_KEYS
                    )
                ) {
                    ManifestFetchResult.NotModified -> recomputeVisibleAnnouncements()
                    is ManifestFetchResult.Updated -> {
                        res.etag?.let { settings.remoteManifestEtag = it }
                        withContext(Dispatchers.IO) {
                            runCatching { cacheFile.writeTextAtomically(res.rawEnvelopeJson) }
                        }
                        applyManifest(res.manifest, notifySystemBar = true)
                    }
                    ManifestFetchResult.Failed -> Unit
                }
            }
        }
    }

    /** 用户在横幅或弹窗上点关闭。可永久关闭的记入 MMKV，不可永久关闭的仅在本次进程会话收起。 */
    fun dismissAnnouncement(announcement: RemoteAnnouncement) {
        sessionDismissedIds.add(announcement.id)
        if (announcement.dismissible) {
            settings.dismissAnnouncement(announcement.id)
        }
        if (_focusedAnnouncement.value?.id == announcement.id) {
            _focusedAnnouncement.value = null
        }
        recomputeVisibleAnnouncements()
    }

    /** 用户点击系统通知栏的公告进入主界面时，聚焦弹出该条公告全文。 */
    fun focusAnnouncementById(id: String?) {
        if (id.isNullOrBlank()) return
        val target = currentManifest.announcements.firstOrNull { it.id == id } ?: return
        _focusedAnnouncement.value = target
    }

    fun clearFocusedAnnouncement() {
        _focusedAnnouncement.value = null
    }

    /** 基于已收集脱敏的 FeedbackPackage 直接压缩上传并返回 6 位求助码（供 FeedbackDialog 零延迟调用）。 */
    suspend fun generateHelpCode(pkg: FeedbackPackage): Result<String> {
        val gzip = withContext(Dispatchers.Default) {
            DiagnosticSnapshotPayload.buildGzipBytes(pkg)
        }
        return client.uploadDiagnosticSnapshot(BuildConfig.REMOTE_OPS_BASE_URL, gzip)
    }

    /** 从报错现场一键收集脱敏快照并上传换取 6 位求助码（供 FailureNotice 直接调用）。 */
    suspend fun generateHelpCode(failure: Failure?): Result<String> {
        val pkg = runCatching { diagnostics.prepare(failure) }.getOrElse { err ->
            return Result.failure(err)
        }
        return generateHelpCode(pkg)
    }

    fun reportDailyHeartbeat() {
        if (!settings.telemetryEnabled) return
        val nowSec = Instant.now().epochSecond
        if (nowSec - settings.lastDailyHeartbeatSeconds < DAILY_HEARTBEAT_INTERVAL_SEC) return
        scope.launch {
            val payload = buildTelemetryPayload(event = "daily_heartbeat")
            if (client.sendTelemetry(BuildConfig.REMOTE_OPS_BASE_URL, telemetryJson.encodeToString(payload))) {
                settings.lastDailyHeartbeatSeconds = nowSec
            }
        }
    }

    fun reportOobeStep(stepName: String, complete: Boolean) {
        if (!settings.telemetryEnabled) return
        scope.launch {
            val payload = buildTelemetryPayload(
                event = "oobe_step",
                oobeStep = stepName,
                setupCompleteOverride = complete
            )
            client.sendTelemetry(BuildConfig.REMOTE_OPS_BASE_URL, telemetryJson.encodeToString(payload))
        }
    }

    fun reportFailure(errorCode: String) {
        if (!settings.telemetryEnabled || errorCode.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val prev = lastFailureReportedMs[errorCode]
        if (prev != null && now - prev < FAILURE_REPORT_COOLDOWN_MS) return
        lastFailureReportedMs[errorCode] = now
        scope.launch {
            val payload = buildTelemetryPayload(event = "failure", errorCode = errorCode)
            client.sendTelemetry(BuildConfig.REMOTE_OPS_BASE_URL, telemetryJson.encodeToString(payload))
        }
    }

    private suspend fun buildTelemetryPayload(
        event: String,
        oobeStep: String? = settings.oobeStep,
        setupCompleteOverride: Boolean = settings.setupComplete,
        errorCode: String? = null
    ): TelemetryPayload = withContext(Dispatchers.IO) {
        val activeQq = runCatching { QqClientChoice(paths).read().name.lowercase() }.getOrNull()
        TelemetryPayload(
            installId = settings.telemetryInstallId(),
            event = event,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            oobeStep = oobeStep,
            setupComplete = setupCompleteOverride,
            activeQqClient = activeQq,
            errorCode = errorCode,
            lastExitReason = latestExitReasonText(appContext)
        )
    }

    private suspend fun applyManifest(manifest: RemoteManifest, notifySystemBar: Boolean) {
        currentManifest = manifest
        updateRemoteFailureHints(manifest.failureHints)
        val env = currentEnvironment()
        val matched = manifest.announcements.filter { it.matches(env) }
        publishVisibleState(matched)
        if (notifySystemBar) {
            postSystemNotificationsIfNeeded(matched)
        }
    }

    fun recomputeVisibleAnnouncements() {
        scope.launch {
            val env = currentEnvironment()
            publishVisibleState(currentManifest.announcements.filter { it.matches(env) })
        }
    }

    private fun publishVisibleState(matched: List<RemoteAnnouncement>) {
        val dismissed = settings.dismissedAnnouncementIds
        val visible = matched.filter { ann ->
            ann.id !in sessionDismissedIds && (!ann.dismissible || ann.id !in dismissed)
        }
        _blockerAnnouncement.value = visible.firstOrNull { it.level == AnnouncementLevel.Blocker }
        _bannerAnnouncements.value = visible.filter { it.level != AnnouncementLevel.Blocker }
    }

    private suspend fun currentEnvironment(): ClientEnvironment = withContext(Dispatchers.IO) {
        val activeQq = runCatching { QqClientChoice(paths).read().name.lowercase() }.getOrNull()
        ClientEnvironment(
            appVersion = BuildConfig.VERSION_NAME,
            manufacturer = Build.MANUFACTURER,
            sdkInt = Build.VERSION.SDK_INT,
            activeQqClient = activeQq,
            setupComplete = settings.setupComplete,
            nowEpochSeconds = Instant.now().epochSecond
        )
    }

    private fun postSystemNotificationsIfNeeded(matched: List<RemoteAnnouncement>) {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) return
        val alreadyNotified = settings.notifiedAnnouncementIds
        val manager = appContext.getSystemService(NotificationManager::class.java)
        matched
            .filter { it.notifySystemBar && it.id !in alreadyNotified }
            .forEach { ann ->
                settings.markAnnouncementNotified(ann.id)
                val intent = Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(EXTRA_ANNOUNCEMENT_ID, ann.id)
                }
                val pending = PendingIntent.getActivity(
                    appContext,
                    ann.id.hashCode(),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val notification = NotificationCompat.Builder(appContext, ANNOUNCEMENT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(ann.title)
                    .setContentText(ann.body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(ann.body))
                    .setAutoCancel(true)
                    .setContentIntent(pending)
                    .build()
                manager.notify(NOTIFICATION_BASE_ID + (ann.id.hashCode() and 0x7FFF), notification)
            }
    }
}
