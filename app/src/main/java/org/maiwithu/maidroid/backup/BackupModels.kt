package org.maiwithu.maidroid.backup

internal const val DEFAULT_EXTERNAL_RELATIVE_PATH = "MaiDroid/Backups"
internal const val DEFAULT_EXTERNAL_DISPLAY_PATH = "/sdcard/MaiDroid/Backups"
internal const val BACKUP_FILE_PREFIX = "maidroid-backup-"
internal const val BACKUP_FILE_SUFFIX = ".zip"
internal const val DEFAULT_SCHEDULE_HOUR = 4
internal const val DEFAULT_SCHEDULE_MINUTE = 0
internal const val DEFAULT_RETENTION_COUNT = 7
internal val RETENTION_OPTIONS = listOf(3, 7, 14, 30)

/** 可纳入备份包的功能组件：覆盖 MaiBot 配置与记忆库、插件、SnowLuma 和 NapCat。 */
internal enum class BackupComponent(
    val label: String,
    val description: String
) {
    MaiBotConfig(
        label = "MaiBot 配置",
        description = "多实例注册表、模型目录与全部实例的 config 配置（含内置适配器）"
    ),
    MaiBotMemory(
        label = "MaiBot 记忆库与数据",
        description = "各实例 data 下的 MaiBot.db、a-memorix 记忆库与状态数据"
    ),
    Plugins(
        label = "插件与插件配置",
        description = "各实例 plugins 目录下的第三方插件与 config.toml（跳过缓存与虚拟环境）"
    ),
    SnowLuma(
        label = "SnowLuma 配置",
        description = "SnowLuma 的 OneBot 配置、协议授权记录与管理页密码"
    ),
    NapCat(
        label = "NapCat 配置",
        description = "NapCat 的 OneBot 配置、账号配置与管理页密码"
    )
}

internal enum class BackupTrigger {
    Manual,
    Scheduled
}

internal data class BackupConfig(
    val autoEnabled: Boolean = false,
    val scheduleHour: Int = DEFAULT_SCHEDULE_HOUR,
    val scheduleMinute: Int = DEFAULT_SCHEDULE_MINUTE,
    val retentionCount: Int = DEFAULT_RETENTION_COUNT,
    val customTreeUri: String = "",
    val customTreeLabel: String = "",
    val components: Set<BackupComponent> = BackupComponent.entries.toSet()
) {
    val usesCustomTree: Boolean get() = customTreeUri.isNotBlank()
}

internal data class BackupRecord(
    val fileName: String,
    val sizeBytes: Long,
    val lastModifiedMillis: Long,
    val storageRef: String
)

internal sealed class BackupRunOutcome {
    data class Success(
        val record: BackupRecord,
        val fileCount: Int,
        val skippedFiles: Int,
        val sqliteSnapshots: Int
    ) : BackupRunOutcome()

    data object PermissionRequired : BackupRunOutcome()
    data class SafAccessLost(val detail: String) : BackupRunOutcome()
    data object EmptySelection : BackupRunOutcome()
    data object NoFilesFound : BackupRunOutcome()
    data class Failed(val reason: String) : BackupRunOutcome()
}

internal data class BackupScheduleSignal(
    val enabled: Boolean,
    val nextTriggerAtMillis: Long?,
    val generation: Long = 0L
)

internal data class BackupState(
    val config: BackupConfig = BackupConfig(),
    val availableComponents: Set<BackupComponent> = BackupComponent.entries.toSet(),
    val running: Boolean = false,
    val progressText: String = "",
    val lastSuccessAtMillis: Long = 0L,
    val lastBackupSummary: String = "",
    val lastError: String = "",
    val nextScheduledAtMillis: Long? = null,
    val records: List<BackupRecord> = emptyList(),
    val loadingRecords: Boolean = false
)
