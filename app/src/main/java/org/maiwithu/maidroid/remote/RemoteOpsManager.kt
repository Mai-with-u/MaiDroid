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
import kotlinx.coroutines.CoroutineStart
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
import org.maiwithu.maidroid.diagnostics.CrashRecorder
import org.maiwithu.maidroid.diagnostics.DiagnosticsManager
import org.maiwithu.maidroid.diagnostics.Failure
import org.maiwithu.maidroid.diagnostics.FailureCause
import org.maiwithu.maidroid.diagnostics.FailureStep
import org.maiwithu.maidroid.diagnostics.FeedbackPackage
import org.maiwithu.maidroid.diagnostics.backgroundScope
import org.maiwithu.maidroid.diagnostics.latestExitReasonText
import org.maiwithu.maidroid.diagnostics.parseFailureLogLine
import org.maiwithu.maidroid.diagnostics.updateRemoteFailureHints
import org.maiwithu.maidroid.maibot.maiBotRecentRunOutput
import org.maiwithu.maidroid.platform.qq.QqClientChoice
import org.maiwithu.maidroid.runtime.MirrorCategory
import org.maiwithu.maidroid.runtime.MirrorManager
import org.maiwithu.maidroid.runtime.NetworkStatus
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.runtime.writeTextAtomically
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.terminal.AnsiText
import org.maiwithu.maidroid.terminal.TerminalLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 远程公告、动态排障热规则、6 位故障求助码上传与无感匿名统计的进程级协调器。
 *
 * 1. 启动即从本地缓存恢复上次验签通过的清单（热规则零延迟生效），随后在打开界面及前台服务每小时后台增量刷新；
 * 2. 匹配当前设备环境的公告拆成顶部横幅列表（info/warning）与模态强提醒（blocker），开启系统栏提醒的新公告只推送一次通知；
 * 3. 匿名运行统计严格遵守用户开关与时间窗口节流：包括纯枚举指标，以及按指纹去重与脱敏清洗后的崩溃堆栈、安装与启动报错。
 */
internal class RemoteOpsManager(
    context: Context,
    private val settings: AppSettings,
    private val paths: RuntimePaths,
    private val crashRecorder: CrashRecorder,
    private val mirrors: MirrorManager,
    private val diagnosticsProvider: () -> DiagnosticsManager
) {
    companion object {
        const val ANNOUNCEMENT_CHANNEL_ID = "maidroid_announcements"
        const val EXTRA_ANNOUNCEMENT_ID = "org.maiwithu.maidroid.extra.ANNOUNCEMENT_ID"

        private const val MIN_FOREGROUND_REFRESH_MS = 5 * 60 * 1000L
        private const val BACKGROUND_POLL_INTERVAL_MS = 60 * 60 * 1000L
        private const val DAILY_HEARTBEAT_INTERVAL_SEC = 24 * 3600L
        private const val FAILURE_REPORT_COOLDOWN_MS = 60 * 60 * 1000L
        private const val AUTO_ERROR_COOLDOWN_SEC = 24 * 3600L
        private const val MAX_AUTO_ERRORS_PER_DAY = 8
        private const val NOTIFICATION_BASE_ID = 2200
        private val AUTO_ERROR_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

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
    private val autoErrorMutex = Mutex()
    private val client = RemoteOpsClient(
        userAgent = "MaiDroid/${BuildConfig.VERSION_NAME}",
        gitHubMirrorsProvider = mirrors::currentGitHubMirrors,
        preferFirstMirror = { mirrors.isManualSelection(MirrorCategory.GitHub) }
    )
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

    @Serializable
    private data class ErrorReportExtraPayload(
        val oobeStep: String? = null,
        val setupComplete: Boolean? = null,
        val activeQqClient: String? = null,
        val availableStorageMb: Long? = null,
        val availableMemMb: Long? = null,
        val lastExitReason: String? = null
    )

    @Serializable
    private data class ErrorReportPayload(
        val installId: String,
        val fingerprint: String,
        val buildType: String,
        val category: String,
        val errorCode: String,
        val appVersion: String,
        val versionCode: Int,
        val manufacturer: String,
        val model: String,
        val sdkInt: Int,
        val title: String,
        val message: String,
        val contextText: String,
        val extra: ErrorReportExtraPayload
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
        // 在 Application 启动即无条件挂上终端失败监听，确保即使失败没有留在 UI 上也能捕获安装与更新现场
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            TerminalLog.lines.collect { line ->
                val (code, message) = parseFailureLogLine(line) ?: return@collect
                reportFailure(code)
                val (step, cause) = AutoErrorExtractor.parseFailureCode(code) ?: return@collect
                val snapshotLines = TerminalLog.logs.value.map(AnsiText::plain)
                scheduleAutoErrorReport(step, cause, message, snapshotLines)
            }
        }
    }

    /** 供 Application.onCreate 显式唤起懒加载，确保终端失败监听在进程第一时间就位。 */
    fun ensureStarted() = Unit

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
        val pkg = runCatching { diagnosticsProvider().prepare(failure) }.getOrElse { err ->
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

    /** 失败提示卡片就绪时同时记错误码计数，并将高价值失败送入脱敏自动聚合上报队列。 */
    fun reportFailure(failure: Failure) {
        reportFailure(failure.code)
        val snapshotLines = TerminalLog.logs.value.map(AnsiText::plain)
        scheduleAutoErrorReport(failure.step, failure.cause, failure.message, snapshotLines)
    }

    /** 后台服务检测到 MaiBot 连续启动退出或受阻时自动调用，抓取纯 Python Traceback 上报。 */
    fun reportMaiBotStartupFailure(failure: Failure) {
        reportFailure(failure.code)
        val snapshotLines = TerminalLog.logs.value.map(AnsiText::plain)
        scheduleAutoErrorReport(failure.step, failure.cause, failure.message, snapshotLines)
    }

    /** 冷启或后台服务拉起时补报尚未送达过的最新本地崩溃堆栈 / 原生崩溃记录。 */
    fun reportPendingCrashIfNeeded() {
        if (!settings.telemetryEnabled) return
        scope.launch(Dispatchers.IO) {
            autoErrorMutex.withLock {
                if (!settings.telemetryEnabled || !NetworkStatus.read(appContext).connected) return@withLock
                val record = crashRecorder.latestCrash() ?: return@withLock
                if (record.timeMillis <= settings.lastAutoReportedCrashMillis) return@withLock
                val crashText = crashRecorder.readCrashFile(record)
                // 旧版本残留的本地崩溃文件不再按当前版本上报，记录为已处理以免升级后误导看板。
                val crashVersion = AutoErrorExtractor.extractCrashHeaderVersion(crashText)
                if (crashVersion != null && !crashVersion.equals(BuildConfig.VERSION_NAME, ignoreCase = true)) {
                    settings.lastAutoReportedCrashMillis = record.timeMillis
                    return@withLock
                }
                val redactor = diagnosticsProvider().createRedactor()
                val draft = AutoErrorExtractor.buildCrashDraft(record.summary, crashText, redactor)
                if (sendAutoErrorLocked(draft)) {
                    settings.lastAutoReportedCrashMillis = record.timeMillis
                }
            }
        }
    }

    private fun scheduleAutoErrorReport(
        step: FailureStep,
        cause: FailureCause?,
        message: String,
        terminalPlainLines: List<String>
    ) {
        if (!settings.telemetryEnabled) return
        val category = AutoErrorExtractor.classifyForAutoReport(step, cause, message) ?: return
        // MD-CRASH 统一由 reportPendingCrashIfNeeded 从本地崩溃堆栈文件提取完整栈帧，不从无栈提示处上报空记录
        if (category == AutoErrorCategory.Crash) return

        scope.launch(Dispatchers.IO) {
            autoErrorMutex.withLock {
                if (!settings.telemetryEnabled || !NetworkStatus.read(appContext).connected) return@withLock
                val diag = diagnosticsProvider()
                val redactor = diag.createRedactor()
                val draft = when (category) {
                    AutoErrorCategory.Install -> AutoErrorExtractor.buildInstallDraft(
                        step = step,
                        cause = cause,
                        message = message,
                        terminalPlainLines = terminalPlainLines,
                        setupComplete = settings.setupComplete,
                        redactor = redactor
                    )
                    AutoErrorCategory.MaiBotBoot -> {
                        val recentRunLines = maiBotRecentRunOutput(terminalPlainLines).lines()
                        val hasTbInTerminal = recentRunLines.any { it.contains("Traceback (most recent call last):") }
                        val fallbackTail = if (hasTbInTerminal) null else diag.readNewestMaiBotLogTail()
                        AutoErrorExtractor.buildMaiBotBootDraft(
                            step = step,
                            cause = cause,
                            message = message,
                            recentRunLines = recentRunLines,
                            fallbackLogTail = fallbackTail,
                            redactor = redactor
                        )
                    }
                    AutoErrorCategory.Crash -> return@withLock
                }
                sendAutoErrorLocked(draft)
            }
        }
    }

    private suspend fun sendAutoErrorLocked(draft: AutoErrorDraft): Boolean {
        val nowSec = Instant.now().epochSecond
        if (settings.wasAutoErrorFingerprintReportedRecently(draft.fingerprint, nowSec, AUTO_ERROR_COOLDOWN_SEC)) {
            return true
        }
        val dayKey = AUTO_ERROR_DAY_FORMAT.format(LocalDate.now())
        if (!settings.canSendAutoErrorToday(dayKey, MAX_AUTO_ERRORS_PER_DAY)) {
            return true
        }

        val lightMeta = diagnosticsProvider().collectLightMeta()
        val activeQq = runCatching { QqClientChoice(paths).read().name.lowercase() }.getOrNull()
        val payload = ErrorReportPayload(
            installId = settings.telemetryInstallId(),
            fingerprint = draft.fingerprint,
            buildType = BuildConfig.BUILD_TYPE,
            category = draft.category.wireValue,
            errorCode = draft.errorCode,
            appVersion = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            sdkInt = Build.VERSION.SDK_INT,
            title = draft.title,
            message = draft.message,
            contextText = draft.contextText,
            extra = ErrorReportExtraPayload(
                // OOBE 完成后 settings.oobeStep 仍保留最后一步 Install，仅未完成引导时才上报步骤以免看板误导
                oobeStep = if (settings.setupComplete) null else settings.oobeStep,
                setupComplete = settings.setupComplete,
                activeQqClient = activeQq,
                availableStorageMb = lightMeta.availableStorageMb,
                availableMemMb = lightMeta.availableMemMb,
                lastExitReason = lightMeta.lastExitReason
            )
        )
        val ok = client.sendErrorReport(
            BuildConfig.REMOTE_OPS_BASE_URL,
            telemetryJson.encodeToString(payload)
        )
        if (ok) {
            settings.markAutoErrorFingerprintReported(draft.fingerprint, nowSec)
            settings.recordAutoErrorSentToday(dayKey)
        }
        return ok
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
