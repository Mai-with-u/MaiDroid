package org.maiwithu.maidroid.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.maiwithu.maidroid.appupdate.formatBytes
import org.maiwithu.maidroid.maibot.maiBotProjectVersion
import org.maiwithu.maidroid.permission.hasExternalStoragePermission
import org.maiwithu.maidroid.runtime.RuntimePaths
import org.maiwithu.maidroid.settings.AppSettings
import org.maiwithu.maidroid.terminal.TerminalLog
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.core.net.toUri

/**
 * 统一协调各组件配置与记忆库的手动备份、每日定时自动备份、存储目标切换及旧备份裁剪：
 * 支持默认 `/sdcard/MaiDroid/Backups` 与 SAF 自定义文件夹两种目标，并用互斥锁串行化写删操作。
 */
internal class BackupManager(
    context: Context,
    private val settings: AppSettings,
    private val paths: RuntimePaths,
    private val appVersionName: String
) {
    private companion object {
        const val AUTO_BACKUP_JOB_ID = 4004
        const val JOB_FLEX_WINDOW_DEFAULT_MS = 2 * 60 * 60 * 1000L
        const val JOB_FLEX_WINDOW_6H_MS = 60 * 60 * 1000L
        val ARCHIVE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val planner = BackupArchivePlanner(paths)
    private val archiver = BackupArchiver()
    private var scheduleGeneration = 0L

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<BackupState> = _state.asStateFlow()

    private val _scheduleSignal = MutableStateFlow(buildScheduleSignal(_state.value.config, _state.value.nextScheduledAtMillis))
    val scheduleSignal: StateFlow<BackupScheduleSignal> = _scheduleSignal.asStateFlow()

    fun setAutoEnabled(enabled: Boolean) {
        settings.backupAutoEnabled = enabled
        updateConfigAndSyncSchedule()
    }

    fun setScheduleFrequency(frequency: BackupFrequency) {
        settings.backupScheduleFrequency = frequency
        updateConfigAndSyncSchedule()
    }

    fun setScheduleDayOfWeek(dayOfWeek: Int) {
        settings.backupScheduleDayOfWeek = dayOfWeek.coerceIn(1, 7)
        updateConfigAndSyncSchedule()
    }

    fun setScheduleTime(hour: Int, minute: Int) {
        settings.backupScheduleHour = hour.coerceIn(0, 23)
        settings.backupScheduleMinute = minute.coerceIn(0, 59)
        updateConfigAndSyncSchedule()
    }

    fun setRetentionCount(count: Int) {
        settings.backupRetentionCount = count.coerceIn(1, 30)
        updateConfigAndSyncSchedule(rescheduleJob = false)
    }

    fun setComponentEnabled(component: BackupComponent, enabled: Boolean) {
        when (component) {
            BackupComponent.MaiBotConfig -> settings.backupIncludeMaiBotConfig = enabled
            BackupComponent.MaiBotMemory -> settings.backupIncludeMaiBotMemory = enabled
            BackupComponent.Plugins -> settings.backupIncludePlugins = enabled
            BackupComponent.SnowLuma -> settings.backupIncludeSnowLuma = enabled
            BackupComponent.NapCat -> settings.backupIncludeNapCat = enabled
        }
        updateConfigAndSyncSchedule(rescheduleJob = false)
    }

    /** 绑定用户通过系统目录选择器授予持久写权限的文件夹作为备份目录。 */
    fun setCustomTreeUri(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrDefault(false)
        if (!persisted) return false

        val previousUri = settings.backupTreeUri
        val label = resolveSafTreeLabel(appContext, uri)
        settings.backupTreeUri = uri.toString()
        settings.backupTreeLabel = label
        if (previousUri.isNotBlank() && previousUri != uri.toString()) {
            releaseUriPermissionSafely(previousUri)
        }
        settings.lastBackupError = ""
        _state.value = _state.value.copy(
            config = readConfig(),
            lastError = ""
        )
        scope.launch { refreshRecords() }
        return true
    }

    /** 切回默认外部存储目录 `/sdcard/MaiDroid/Backups`。 */
    fun useDefaultExternalDirectory() {
        val previousUri = settings.backupTreeUri
        settings.backupTreeUri = ""
        settings.backupTreeLabel = ""
        if (previousUri.isNotBlank()) {
            releaseUriPermissionSafely(previousUri)
        }
        settings.lastBackupError = ""
        _state.value = _state.value.copy(
            config = readConfig(),
            lastError = ""
        )
        scope.launch { refreshRecords() }
    }

    fun onClockOrTimezoneChanged() {
        updateConfigAndSyncSchedule()
    }

    /** 按当前开关和下一次计划时刻向系统 `JobScheduler` 注册或取消持久化定时任务。 */
    fun syncJobSchedule() {
        val config = readConfig()
        val now = System.currentTimeMillis()
        val nextMillis = if (config.autoEnabled) {
            nextScheduledBackupMillis(
                nowMillis = now,
                zoneId = ZoneId.systemDefault(),
                hour = config.scheduleHour,
                minute = config.scheduleMinute,
                frequency = config.frequency,
                dayOfWeek = config.scheduleDayOfWeek
            )
        } else {
            null
        }
        applyScheduleState(config, nextMillis)
        val scheduler = appContext.getSystemService(JobScheduler::class.java) ?: return
        if (!config.autoEnabled || nextMillis == null) {
            scheduler.cancel(AUTO_BACKUP_JOB_ID)
            return
        }
        val flexWindowMs = if (config.frequency == BackupFrequency.Every6Hours) {
            JOB_FLEX_WINDOW_6H_MS
        } else {
            JOB_FLEX_WINDOW_DEFAULT_MS
        }
        val minLatencyMs = (nextMillis - now).coerceAtLeast(1_000L)
        val jobInfo = JobInfo.Builder(
            AUTO_BACKUP_JOB_ID,
            ComponentName(appContext, AutoBackupJobService::class.java)
        )
            .setMinimumLatency(minLatencyMs)
            .setOverrideDeadline(minLatencyMs + flexWindowMs)
            .setPersisted(true)
            .build()
        scheduler.schedule(jobInfo)
    }

    suspend fun refreshRecords() {
        val config = readConfig()
        _state.value = _state.value.copy(loadingRecords = true)
        val (available, records, errorText) = withContext(Dispatchers.IO) {
            val detected = planner.detectAvailableComponents()
            if (!config.usesCustomTree && !hasExternalStoragePermission(appContext)) {
                return@withContext Triple(detected, emptyList<BackupRecord>(), "")
            }
            val target = resolveStorageTarget(config)
            val result = try {
                target.listBackups() to ""
            } catch (e: SafAccessLostException) {
                emptyList<BackupRecord>() to (e.message ?: "已选文件夹访问权限已失效")
            } catch (_: Throwable) {
                emptyList<BackupRecord>() to ""
            }
            Triple(detected, result.first, result.second)
        }
        _state.value = _state.value.copy(
            config = config,
            availableComponents = available,
            records = records,
            loadingRecords = false,
            lastError = errorText.ifEmpty { _state.value.lastError }
        )
    }

    suspend fun runManualBackup(): BackupRunOutcome = executeBackup(BackupTrigger.Manual)

    suspend fun runScheduledBackupIfDue(): BackupRunOutcome {
        val config = readConfig()
        if (!config.autoEnabled) return BackupRunOutcome.EmptySelection
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        if (!shouldRunScheduledBackup(
                nowMillis = now,
                zoneId = zone,
                hour = config.scheduleHour,
                minute = config.scheduleMinute,
                lastAutoBackupSlotKey = settings.lastAutoBackupDayKey,
                frequency = config.frequency,
                dayOfWeek = config.scheduleDayOfWeek
            )
        ) {
            syncJobSchedule()
            return BackupRunOutcome.EmptySelection
        }
        val outcome = executeBackup(BackupTrigger.Scheduled)
        syncJobSchedule()
        return outcome
    }

    suspend fun deleteBackup(record: BackupRecord): Boolean = mutex.withLock {
        val config = readConfig()
        val deleted = withContext(Dispatchers.IO) {
            runCatching { resolveStorageTarget(config).deleteBackup(record) }.getOrDefault(false)
        }
        if (deleted) {
            refreshRecordsLocked(config)
        }
        deleted
    }

    private suspend fun executeBackup(trigger: BackupTrigger): BackupRunOutcome = mutex.withLock {
        val config = readConfig()
        if (config.components.isEmpty()) {
            val msg = "请至少选择一项需要备份的内容"
            recordFailure(msg)
            return BackupRunOutcome.EmptySelection
        }
        if (!config.usesCustomTree && !hasExternalStoragePermission(appContext)) {
            val msg = "未授予外部存储权限，无法写入 $DEFAULT_EXTERNAL_DISPLAY_PATH（也可选择自定义文件夹免权限）"
            recordFailure(msg)
            return BackupRunOutcome.PermissionRequired
        }

        _state.value = _state.value.copy(
            config = config,
            running = true,
            progressText = "正在扫描需要备份的文件...",
            lastError = ""
        )

        val stagingRoot = File(appContext.cacheDir, "backup-staging")
        val tempZip = File(stagingRoot, "staging.zip")
        val snapshotDir = File(stagingRoot, "sqlite")
        try {
            val available = withContext(Dispatchers.IO) { planner.detectAvailableComponents() }
            _state.value = _state.value.copy(availableComponents = available)
            // 先按当前实际已安装组件裁剪勾选集合，防止只剩未安装协议端被默认勾选时误触发扫描。
            val effectiveComponents = config.components intersect available
            if (effectiveComponents.isEmpty()) {
                val msg = "请至少选择一项需要备份的内容"
                recordFailure(msg)
                return BackupRunOutcome.EmptySelection
            }

            val entries = withContext(Dispatchers.IO) { planner.plan(effectiveComponents) }
            if (entries.isEmpty()) {
                val msg = "所选组件当前没有可备份的文件"
                recordFailure(msg)
                return BackupRunOutcome.NoFilesFound
            }

            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val timestamp = Instant.ofEpochMilli(now).atZone(zone).format(ARCHIVE_TIME_FORMATTER)
            val fileName = "$BACKUP_FILE_PREFIX$timestamp$BACKUP_FILE_SUFFIX"
            val maiBotVersion = withContext(Dispatchers.IO) { readMaiBotVersion() }

            val buildResult = withContext(Dispatchers.IO) {
                archiver.buildArchive(
                    entries = entries,
                    outputZip = tempZip,
                    snapshotDir = snapshotDir,
                    appVersion = appVersionName,
                    maiBotVersion = maiBotVersion,
                    trigger = trigger,
                    components = effectiveComponents,
                    createdAtMillis = now,
                    onProgress = { processed, total ->
                        _state.value = _state.value.copy(
                            progressText = "正在打包（$processed/$total）..."
                        )
                    }
                )
            }

            _state.value = _state.value.copy(progressText = "正在保存到备份目录...")
            val target = resolveStorageTarget(config)
            val record = withContext(Dispatchers.IO) {
                val saved = target.writeArchive(buildResult.zipFile, fileName)
                target.pruneOldBackups(config.retentionCount)
                saved
            }

            val summary = buildSuccessSummary(record.sizeBytes, buildResult.fileCount, buildResult.sqliteSnapshots)
            settings.lastBackupAtMillis = now
            settings.lastBackupSummary = summary
            settings.lastBackupError = ""
            if (trigger == BackupTrigger.Scheduled) {
                settings.lastAutoBackupDayKey = currentAutoBackupSlotKey(
                    nowMillis = now,
                    zoneId = zone,
                    hour = config.scheduleHour,
                    minute = config.scheduleMinute,
                    frequency = config.frequency,
                    dayOfWeek = config.scheduleDayOfWeek
                )
            }
            TerminalLog.append("[备份] ${if (trigger == BackupTrigger.Scheduled) "定时" else "手动"}备份完成：${record.fileName}（$summary）")

            _state.value = _state.value.copy(
                running = false,
                progressText = "",
                lastSuccessAtMillis = now,
                lastBackupSummary = summary,
                lastError = ""
            )
            refreshRecordsLocked(config)
            return BackupRunOutcome.Success(
                record = record,
                fileCount = buildResult.fileCount,
                skippedFiles = buildResult.skippedFiles,
                sqliteSnapshots = buildResult.sqliteSnapshots
            )
        } catch (e: SafAccessLostException) {
            val detail = e.message ?: "自定义文件夹访问权限已失效，请重新选择"
            recordFailure(detail)
            return BackupRunOutcome.SafAccessLost(detail)
        } catch (e: Throwable) {
            val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            recordFailure("备份失败：$reason")
            TerminalLog.append("[备份] 失败：$reason")
            return BackupRunOutcome.Failed(reason)
        } finally {
            tempZip.delete()
            snapshotDir.deleteRecursively()
        }
    }

    private suspend fun refreshRecordsLocked(config: BackupConfig) {
        val records = withContext(Dispatchers.IO) {
            if (!config.usesCustomTree && !hasExternalStoragePermission(appContext)) {
                emptyList()
            } else {
                runCatching { resolveStorageTarget(config).listBackups() }.getOrDefault(emptyList())
            }
        }
        _state.value = _state.value.copy(records = records)
    }

    private fun resolveStorageTarget(config: BackupConfig): BackupStorageTarget =
        if (config.usesCustomTree) {
            SafTreeBackupTarget(appContext, config.customTreeUri.toUri())
        } else {
            FileDirectoryBackupTarget(defaultExternalBackupDir())
        }

    private fun recordFailure(message: String) {
        settings.lastBackupError = message
        _state.value = _state.value.copy(
            running = false,
            progressText = "",
            lastError = message
        )
    }

    private fun updateConfigAndSyncSchedule(rescheduleJob: Boolean = true) {
        val config = readConfig()
        val nextMillis = if (config.autoEnabled) {
            nextScheduledBackupMillis(
                nowMillis = System.currentTimeMillis(),
                zoneId = ZoneId.systemDefault(),
                hour = config.scheduleHour,
                minute = config.scheduleMinute,
                frequency = config.frequency,
                dayOfWeek = config.scheduleDayOfWeek
            )
        } else {
            null
        }
        applyScheduleState(config, nextMillis)
        if (rescheduleJob) {
            syncJobSchedule()
        }
    }

    private fun applyScheduleState(config: BackupConfig, nextMillis: Long?) {
        scheduleGeneration++
        _state.value = _state.value.copy(
            config = config,
            nextScheduledAtMillis = nextMillis
        )
        _scheduleSignal.value = buildScheduleSignal(config, nextMillis)
    }

    private fun buildScheduleSignal(config: BackupConfig, nextMillis: Long?): BackupScheduleSignal =
        BackupScheduleSignal(
            enabled = config.autoEnabled,
            nextTriggerAtMillis = nextMillis,
            generation = scheduleGeneration
        )

    private fun buildInitialState(): BackupState {
        val config = readConfig()
        val nextMillis = if (config.autoEnabled) {
            nextScheduledBackupMillis(
                nowMillis = System.currentTimeMillis(),
                zoneId = ZoneId.systemDefault(),
                hour = config.scheduleHour,
                minute = config.scheduleMinute,
                frequency = config.frequency,
                dayOfWeek = config.scheduleDayOfWeek
            )
        } else {
            null
        }
        return BackupState(
            config = config,
            availableComponents = planner.detectAvailableComponents(),
            lastSuccessAtMillis = settings.lastBackupAtMillis,
            lastBackupSummary = settings.lastBackupSummary,
            lastError = settings.lastBackupError,
            nextScheduledAtMillis = nextMillis
        )
    }

    private fun readConfig(): BackupConfig {
        val components = buildSet {
            if (settings.backupIncludeMaiBotConfig) add(BackupComponent.MaiBotConfig)
            if (settings.backupIncludeMaiBotMemory) add(BackupComponent.MaiBotMemory)
            if (settings.backupIncludePlugins) add(BackupComponent.Plugins)
            if (settings.backupIncludeSnowLuma) add(BackupComponent.SnowLuma)
            if (settings.backupIncludeNapCat) add(BackupComponent.NapCat)
        }
        return BackupConfig(
            autoEnabled = settings.backupAutoEnabled,
            frequency = settings.backupScheduleFrequency,
            scheduleDayOfWeek = settings.backupScheduleDayOfWeek,
            scheduleHour = settings.backupScheduleHour,
            scheduleMinute = settings.backupScheduleMinute,
            retentionCount = settings.backupRetentionCount,
            customTreeUri = settings.backupTreeUri,
            customTreeLabel = settings.backupTreeLabel,
            components = components
        )
    }

    private fun releaseUriPermissionSafely(uriString: String) {
        runCatching {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            appContext.contentResolver.releasePersistableUriPermission(uriString.toUri(), flags)
        }
    }

    private fun readMaiBotVersion(): String? =
        runCatching { paths.maiBotPyproject.takeIf { it.isFile }?.readText() }
            .getOrNull()
            ?.let(::maiBotProjectVersion)

    private fun buildSuccessSummary(sizeBytes: Long, fileCount: Int, sqliteSnapshots: Int): String {
        val base = "${formatBytes(sizeBytes)} · $fileCount 个文件"
        return if (sqliteSnapshots > 0) "$base · $sqliteSnapshots 个记忆库在线快照" else base
    }
}
