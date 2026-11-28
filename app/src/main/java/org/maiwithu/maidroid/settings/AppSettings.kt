package org.maiwithu.maidroid.settings

import android.util.Log
import com.tencent.mmkv.MMKV
import com.tencent.mmkv.MMKVHandler
import com.tencent.mmkv.MMKVLogLevel
import com.tencent.mmkv.MMKVRecoverStrategic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maiwithu.maidroid.pet.DesktopPetScaleSpec
import org.maiwithu.maidroid.pet.normalizeDesktopPetScale
import org.maiwithu.maidroid.backup.BackupFrequency
import org.maiwithu.maidroid.backup.DEFAULT_SCHEDULE_DAY_OF_WEEK
import org.maiwithu.maidroid.runtime.MirrorCategory
import org.maiwithu.maidroid.runtime.MirrorPreference
import org.maiwithu.maidroid.runtime.MirrorPreferenceStore
import org.maiwithu.maidroid.runtime.MirrorSelectionMode
import org.maiwithu.maidroid.terminal.TerminalLog
import org.maiwithu.maidroid.ssh.ContainerSshPortRange
import java.util.UUID

/**
 * 用 MMKV 持久化的应用设置：OOBE 是否完成及断点步骤、是否同意协议、自启开关、容器 SSH 配置、
 * 上次打开时的 MaiDroid 版本号、MaiBot 自动升级开关、是否安全退出、QQ 自定义安装包来源、三类镜像源配置以及竖屏悬浮胶囊停靠位置。
 * MMKV 写入即落盘，读写都很轻，可以在主线程直接访问。既有键名在用户设备上存在，不要随意改动。
 */
internal class AppSettings(
    private val kv: MMKV = MMKV.defaultMMKV()
) : MirrorPreferenceStore {

    private companion object {
        const val KEY_SETUP_COMPLETE = "setup_complete"
        const val KEY_AUTHORIZATION_ACCEPTED = "authorization_accepted"
        const val KEY_SERVICE_AUTO_START = "service_auto_start"
        const val KEY_CONTAINER_SSH_ENABLED = "container_ssh_enabled"
        const val KEY_CONTAINER_SSH_CONFIGURED = "container_ssh_configured"
        const val KEY_CONTAINER_SSH_PORT = "container_ssh_port"
        const val KEY_CONTAINER_SSH_ROOT_PASSWORD = "container_ssh_root_password"
        const val KEY_LAST_LAUNCHED_VERSION_CODE = "last_launched_version_code"
        const val KEY_MAIBOT_AUTO_UPDATE_DISABLED = "maibot_auto_update_disabled"
        const val KEY_USER_EXITED = "user_exited"
        const val KEY_OOBE_STEP = "oobe_step"
        const val KEY_NAPCAT_CUSTOM_SOURCE_URL = "napcat_custom_source_url"
        const val KEY_SNOWLUMA_CUSTOM_SOURCE_URL = "snowluma_custom_source_url"
        const val KEY_WEBUI_PILL_DOCK_RIGHT = "webui_pill_dock_right"
        const val KEY_WEBUI_PILL_VERTICAL_BIAS = "webui_pill_vertical_bias"
        const val KEY_DESKTOP_PET_BOT_ID = "desktop_pet_bot_id"
        const val KEY_DESKTOP_PET_CLIENT_ID = "desktop_pet_client_id"
        const val KEY_DESKTOP_PET_SCALE = "desktop_pet_scale"
        const val KEY_TELEMETRY_ENABLED = "telemetry_enabled"
        const val KEY_TELEMETRY_INSTALL_ID = "telemetry_install_id"
        const val KEY_LAST_HEARTBEAT_SECONDS = "telemetry_last_heartbeat_sec"
        const val KEY_LAST_ONLINE_HEARTBEAT_SECONDS = "telemetry_last_online_heartbeat_sec"
        const val KEY_LAST_TELEMETRY_VERSION_CODE = "telemetry_last_version_code"
        const val KEY_LAST_TELEMETRY_VERSION_NAME = "telemetry_last_version_name"
        const val KEY_LAST_AUTO_CRASH_MILLIS = "telemetry_last_auto_crash_ms"
        const val KEY_AUTO_ERROR_DAY = "telemetry_auto_error_day"
        const val KEY_AUTO_ERROR_DAY_COUNT = "telemetry_auto_error_day_count"
        const val KEY_AUTO_ERROR_FPS = "telemetry_auto_error_fps"
        const val KEY_REMOTE_MANIFEST_ETAG = "remote_manifest_etag"
        const val KEY_DISMISSED_ANNOUNCEMENTS = "dismissed_announcement_ids"
        const val KEY_NOTIFIED_ANNOUNCEMENTS = "notified_announcement_ids"
        const val KEY_MIRROR_GITHUB_MODE = "mirror_github_mode"
        const val KEY_MIRROR_GITHUB_PRESET = "mirror_github_preset"
        const val KEY_MIRROR_GITHUB_CUSTOM = "mirror_github_custom"
        const val KEY_MIRROR_APT_MODE = "mirror_apt_mode"
        const val KEY_MIRROR_APT_PRESET = "mirror_apt_preset"
        const val KEY_MIRROR_APT_CUSTOM = "mirror_apt_custom"
        const val KEY_MIRROR_PYPI_MODE = "mirror_pypi_mode"
        const val KEY_MIRROR_PYPI_PRESET = "mirror_pypi_preset"
        const val KEY_MIRROR_PYPI_CUSTOM = "mirror_pypi_custom"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        const val KEY_WIFI_LOCK_ENABLED = "wifi_lock_enabled"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_BACKUP_AUTO_ENABLED = "backup_auto_enabled"
        const val KEY_BACKUP_SCHEDULE_FREQUENCY = "backup_schedule_frequency"
        const val KEY_BACKUP_SCHEDULE_DAY_OF_WEEK = "backup_schedule_day_of_week"
        const val KEY_BACKUP_SCHEDULE_HOUR = "backup_schedule_hour"
        const val KEY_BACKUP_SCHEDULE_MINUTE = "backup_schedule_minute"
        const val KEY_BACKUP_RETENTION_COUNT = "backup_retention_count"
        const val KEY_BACKUP_TREE_URI = "backup_tree_uri"
        const val KEY_BACKUP_TREE_LABEL = "backup_tree_label"
        const val KEY_BACKUP_INCLUDE_MAIBOT_CONFIG = "backup_include_maibot_config"
        const val KEY_BACKUP_INCLUDE_MAIBOT_MEMORY = "backup_include_maibot_memory"
        const val KEY_BACKUP_INCLUDE_PLUGINS = "backup_include_plugins"
        const val KEY_BACKUP_INCLUDE_SNOWLUMA = "backup_include_snowluma"
        const val KEY_BACKUP_INCLUDE_NAPCAT = "backup_include_napcat"
        const val KEY_LAST_AUTO_BACKUP_DAY_KEY = "backup_last_auto_day"
        const val KEY_LAST_BACKUP_AT_MILLIS = "backup_last_at_ms"
        const val KEY_LAST_BACKUP_SUMMARY = "backup_last_summary"
        const val KEY_LAST_BACKUP_ERROR = "backup_last_error"

        const val DEFAULT_CONTAINER_SSH_PORT = 2222
        const val DEFAULT_WEBUI_PILL_VERTICAL_BIAS = 0.65f
        const val MAX_TRACKED_ANNOUNCEMENT_IDS = 64
        const val MAX_TRACKED_ERROR_FINGERPRINTS = 32
    }

    /** OOBE 全部完成（MaiBot 装好且 WebUI 起来过），之后启动直接进主页。 */
    var setupComplete: Boolean
        get() = kv.decodeBool(KEY_SETUP_COMPLETE, false)
        set(value) {
            kv.encode(KEY_SETUP_COMPLETE, value)
        }

    /** 用户在首次启动的授权弹窗里同意了用户协议和隐私协议。 */
    var authorizationAccepted: Boolean
        get() = kv.decodeBool(KEY_AUTHORIZATION_ACCEPTED, false)
        set(value) {
            kv.encode(KEY_AUTHORIZATION_ACCEPTED, value)
        }

    /** 开机或应用覆盖升级后是否自动拉起 MaiBot 前台服务。目前没有界面开关，默认开启。 */
    val autoStartService: Boolean
        get() = kv.decodeBool(KEY_SERVICE_AUTO_START, true)

    var containerSshEnabled: Boolean
        get() = kv.decodeBool(KEY_CONTAINER_SSH_ENABLED, false)
        set(value) {
            kv.encode(KEY_CONTAINER_SSH_ENABLED, value)
        }

    /** 保存过端口和 root 密码；没配置过时不允许开启 SSH。 */
    val containerSshConfigured: Boolean
        get() = kv.decodeBool(KEY_CONTAINER_SSH_CONFIGURED, false)

    val containerSshPort: Int
        get() = kv.decodeInt(KEY_CONTAINER_SSH_PORT, DEFAULT_CONTAINER_SSH_PORT).coerceIn(ContainerSshPortRange)

    val containerSshRootPassword: String
        get() = kv.decodeString(KEY_CONTAINER_SSH_ROOT_PASSWORD, "") ?: ""

    /**
     * 上一次打开主界面时 MaiDroid 的版本号（versionCode）；比当前小说明 MaiDroid 刚更新过。
     * 记录这个值之前的旧版本升上来时读到 0，同样算刚更新过。
     */
    var lastLaunchedVersionCode: Int
        get() = kv.decodeInt(KEY_LAST_LAUNCHED_VERSION_CODE, 0)
        set(value) {
            kv.encode(KEY_LAST_LAUNCHED_VERSION_CODE, value)
        }

    /**
     * 用户在设置页“应用版本”弹窗底部打开了“关闭 MaiBot 自动更新”：MaiDroid 更新后首次启动时不再自动升级 MaiBot。
     * 默认 false，即自动升级。只管这一次自动升级，启动时的 MaiBot 新版本提醒不受它影响。
     */
    var maiBotAutoUpdateDisabled: Boolean
        get() = kv.decodeBool(KEY_MAIBOT_AUTO_UPDATE_DISABLED, false)
        set(value) {
            kv.encode(KEY_MAIBOT_AUTO_UPDATE_DISABLED, value)
        }

    /**
     * 用户在设置页点了“安全退出”，之后还没再用过 MaiDroid。为 true 时开机不自动拉起麦麦（和强行停止应用后一样，
     * 等用户自己打开）；前台服务收到应用里发来的任何启动请求时清掉。
     */
    var userExited: Boolean
        get() = kv.decodeBool(KEY_USER_EXITED, false)
        set(value) {
            kv.encode(KEY_USER_EXITED, value)
        }

    /**
     * 首次引导走到了哪一步（OobeStep 的名字），没走过时为 null。应用被划掉、闪退、强制重启、断电后重新打开，
     * 直接回到这一步接着装，不用再从权限页点过来；以前只存在界面的保存状态里，这些情况下都会丢。
     */
    var oobeStep: String?
        get() = kv.decodeString(KEY_OOBE_STEP, null)
        set(value) {
            kv.encode(KEY_OOBE_STEP, value)
        }

    /**
     * 用户自己填写的 NapCat 自定义仓库或压缩包直链；默认留空，不内置第三方 fork。
     * 未填时下载安装会弹窗让用户填写，已安装后可在 NapCat 配置弹窗里修改或重装。
     */
    var napCatCustomSourceUrl: String
        get() = kv.decodeString(KEY_NAPCAT_CUSTOM_SOURCE_URL, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_NAPCAT_CUSTOM_SOURCE_URL, value.trim())
        }

    /**
     * 用户自己填写的 SnowLuma 自定义仓库或发行包直链；默认留空，不内置第三方 fork。
     * 未填时下载安装会弹窗让用户填写，已安装后可在 SnowLuma 配置弹窗里修改或重装。
     */
    var snowLumaCustomSourceUrl: String
        get() = kv.decodeString(KEY_SNOWLUMA_CUSTOM_SOURCE_URL, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_SNOWLUMA_CUSTOM_SOURCE_URL, value.trim())
        }

    /** 竖屏 WebUI 侧边胶囊是否停靠在屏幕右侧；默认停在左侧（false），符合单手握持与滑屏习惯。 */
    var webuiPillDockRight: Boolean
        get() = kv.decodeBool(KEY_WEBUI_PILL_DOCK_RIGHT, false)
        set(value) {
            kv.encode(KEY_WEBUI_PILL_DOCK_RIGHT, value)
        }

    /** 竖屏 WebUI 侧边胶囊沿屏高的垂直停靠比例，限定在 [0f, 1f]。 */
    var webuiPillVerticalBias: Float
        get() = kv.decodeFloat(KEY_WEBUI_PILL_VERTICAL_BIAS, DEFAULT_WEBUI_PILL_VERTICAL_BIAS).coerceIn(0f, 1f)
        set(value) {
            kv.encode(KEY_WEBUI_PILL_VERTICAL_BIAS, value.coerceIn(0f, 1f))
        }

    /** 悬浮桌宠上次选中的本机机器人 ID。 */
    var desktopPetBotId: String
        get() = kv.decodeString(KEY_DESKTOP_PET_BOT_ID, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_DESKTOP_PET_BOT_ID, value.trim())
        }

    /** 悬浮桌宠画面缩放比例（默认 1.0，范围 0.60–1.30，5% 步进）。 */
    var desktopPetScale: Float
        get() = normalizeDesktopPetScale(kv.decodeFloat(KEY_DESKTOP_PET_SCALE, DesktopPetScaleSpec.DEFAULT))
        set(value) {
            kv.encode(KEY_DESKTOP_PET_SCALE, normalizeDesktopPetScale(value))
        }

    /** 悬浮桌宠向本机服务器声明的稳定客户端 ID（32 位十六进制），首次读取时生成并持久化。 */
    fun desktopPetClientId(): String {
        val existing = kv.decodeString(KEY_DESKTOP_PET_CLIENT_ID, null)?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString().replace("-", "")
        kv.encode(KEY_DESKTOP_PET_CLIENT_ID, generated)
        return generated
    }

    fun saveContainerSsh(port: Int, rootPassword: String) {
        kv.encode(KEY_CONTAINER_SSH_PORT, port.coerceIn(ContainerSshPortRange))
        kv.encode(KEY_CONTAINER_SSH_ROOT_PASSWORD, rootPassword)
        kv.encode(KEY_CONTAINER_SSH_CONFIGURED, true)
    }

    /** 是否允许发送匿名运行统计（含匿名版本、脱敏崩溃堆栈与安装/启动报错片段，绝不含聊天内容与密钥）；默认开启，可在设置页随时关闭。 */
    var telemetryEnabled: Boolean
        get() = kv.decodeBool(KEY_TELEMETRY_ENABLED, true)
        set(value) {
            kv.encode(KEY_TELEMETRY_ENABLED, value)
        }

    // 主界面与全屏网页共用这条状态流，设置页切开关时窗口常亮标记会立即增删。
    private val _keepScreenOn = MutableStateFlow(kv.decodeBool(KEY_KEEP_SCREEN_ON, false))
    val keepScreenOnFlow: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    /** 前台打开界面时是否阻止系统自动息屏；默认关闭以降低高刷大屏机型亮屏挂机导致的发热与边充边掉电。 */
    var keepScreenOn: Boolean
        get() = _keepScreenOn.value
        set(value) {
            kv.encode(KEY_KEEP_SCREEN_ON, value)
            _keepScreenOn.value = value
        }

    // 与前台服务实时联动，默认关闭低延迟 Wi-Fi 锁以免阻止 Wi-Fi 射频进入常规节能休眠。
    private val _wifiLockEnabled = MutableStateFlow(kv.decodeBool(KEY_WIFI_LOCK_ENABLED, false))
    val wifiLockEnabledFlow: StateFlow<Boolean> = _wifiLockEnabled.asStateFlow()

    /** 后台运行期间是否持有 Wi-Fi 低延迟锁防止息屏断连；默认关闭以降低射频耗电与发热。 */
    var wifiLockEnabled: Boolean
        get() = _wifiLockEnabled.value
        set(value) {
            kv.encode(KEY_WIFI_LOCK_ENABLED, value)
            _wifiLockEnabled.value = value
        }

    // 与主界面/WebUI 共用外观主题状态流：设置页切换“跟随系统/浅色/深色”时整站立即重组响应。
    private val _themeMode = MutableStateFlow(AppThemeMode.fromStorage(kv.decodeString(KEY_THEME_MODE, null)))
    val themeModeFlow: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    /** 外观主题模式：默认跟随系统，允许手动指定浅色或深色并即时落盘。 */
    var themeMode: AppThemeMode
        get() = _themeMode.value
        set(value) {
            kv.encode(KEY_THEME_MODE, value.storageValue)
            _themeMode.value = value
        }

    // ── 自动备份设置与最近状态 ────────────────────────────────────────────
    var backupAutoEnabled: Boolean
        get() = kv.decodeBool(KEY_BACKUP_AUTO_ENABLED, false)
        set(value) {
            kv.encode(KEY_BACKUP_AUTO_ENABLED, value)
        }

    var backupScheduleFrequency: BackupFrequency
        get() = BackupFrequency.fromStorageValue(kv.decodeString(KEY_BACKUP_SCHEDULE_FREQUENCY, null))
        set(value) {
            kv.encode(KEY_BACKUP_SCHEDULE_FREQUENCY, value.storageValue)
        }

    var backupScheduleDayOfWeek: Int
        get() = kv.decodeInt(KEY_BACKUP_SCHEDULE_DAY_OF_WEEK, DEFAULT_SCHEDULE_DAY_OF_WEEK).coerceIn(1, 7)
        set(value) {
            kv.encode(KEY_BACKUP_SCHEDULE_DAY_OF_WEEK, value.coerceIn(1, 7))
        }

    var backupScheduleHour: Int
        get() = kv.decodeInt(KEY_BACKUP_SCHEDULE_HOUR, 4).coerceIn(0, 23)
        set(value) {
            kv.encode(KEY_BACKUP_SCHEDULE_HOUR, value.coerceIn(0, 23))
        }

    var backupScheduleMinute: Int
        get() = kv.decodeInt(KEY_BACKUP_SCHEDULE_MINUTE, 0).coerceIn(0, 59)
        set(value) {
            kv.encode(KEY_BACKUP_SCHEDULE_MINUTE, value.coerceIn(0, 59))
        }

    var backupRetentionCount: Int
        get() = kv.decodeInt(KEY_BACKUP_RETENTION_COUNT, 7).coerceIn(1, 30)
        set(value) {
            kv.encode(KEY_BACKUP_RETENTION_COUNT, value.coerceIn(1, 30))
        }

    var backupTreeUri: String
        get() = kv.decodeString(KEY_BACKUP_TREE_URI, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_BACKUP_TREE_URI, value.trim())
        }

    var backupTreeLabel: String
        get() = kv.decodeString(KEY_BACKUP_TREE_LABEL, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_BACKUP_TREE_LABEL, value.trim())
        }

    var backupIncludeMaiBotConfig: Boolean
        get() = kv.decodeBool(KEY_BACKUP_INCLUDE_MAIBOT_CONFIG, true)
        set(value) {
            kv.encode(KEY_BACKUP_INCLUDE_MAIBOT_CONFIG, value)
        }

    var backupIncludeMaiBotMemory: Boolean
        get() = kv.decodeBool(KEY_BACKUP_INCLUDE_MAIBOT_MEMORY, true)
        set(value) {
            kv.encode(KEY_BACKUP_INCLUDE_MAIBOT_MEMORY, value)
        }

    var backupIncludePlugins: Boolean
        get() = kv.decodeBool(KEY_BACKUP_INCLUDE_PLUGINS, true)
        set(value) {
            kv.encode(KEY_BACKUP_INCLUDE_PLUGINS, value)
        }

    var backupIncludeSnowLuma: Boolean
        get() = kv.decodeBool(KEY_BACKUP_INCLUDE_SNOWLUMA, true)
        set(value) {
            kv.encode(KEY_BACKUP_INCLUDE_SNOWLUMA, value)
        }

    var backupIncludeNapCat: Boolean
        get() = kv.decodeBool(KEY_BACKUP_INCLUDE_NAPCAT, true)
        set(value) {
            kv.encode(KEY_BACKUP_INCLUDE_NAPCAT, value)
        }

    var lastAutoBackupDayKey: String
        get() = kv.decodeString(KEY_LAST_AUTO_BACKUP_DAY_KEY, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_LAST_AUTO_BACKUP_DAY_KEY, value.trim())
        }

    var lastBackupAtMillis: Long
        get() = kv.decodeLong(KEY_LAST_BACKUP_AT_MILLIS, 0L)
        set(value) {
            kv.encode(KEY_LAST_BACKUP_AT_MILLIS, value)
        }

    var lastBackupSummary: String
        get() = kv.decodeString(KEY_LAST_BACKUP_SUMMARY, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_LAST_BACKUP_SUMMARY, value.trim())
        }

    var lastBackupError: String
        get() = kv.decodeString(KEY_LAST_BACKUP_ERROR, "")?.trim().orEmpty()
        set(value) {
            kv.encode(KEY_LAST_BACKUP_ERROR, value.trim())
        }

    /** 匿名安装随机标识：首次读取时生成 UUID，卸载重装即变，不绑定任何硬件序列号或账号。 */
    fun telemetryInstallId(): String {
        val existing = kv.decodeString(KEY_TELEMETRY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }
        if (existing != null) return existing
        val generated = UUID.randomUUID().toString()
        kv.encode(KEY_TELEMETRY_INSTALL_ID, generated)
        return generated
    }

    /** 上次成功发送每日存活心跳的时间戳（秒），用于 24 小时节流。 */
    var lastDailyHeartbeatSeconds: Long
        get() = kv.decodeLong(KEY_LAST_HEARTBEAT_SECONDS, 0L)
        set(value) {
            kv.encode(KEY_LAST_HEARTBEAT_SECONDS, value)
        }

    /** 上次成功发送实时在线心跳的时间戳（秒），用于 10 分钟心跳与 60 秒强制触发节流。 */
    var lastOnlineHeartbeatSeconds: Long
        get() = kv.decodeLong(KEY_LAST_ONLINE_HEARTBEAT_SECONDS, 0L)
        set(value) {
            kv.encode(KEY_LAST_ONLINE_HEARTBEAT_SECONDS, value)
        }

    /** 最近一次已成功上报给远端遥测的 MaiDroid versionCode，版本升级时立即放行新版本心跳。 */
    var lastTelemetryReportedVersionCode: Int
        get() = kv.decodeInt(KEY_LAST_TELEMETRY_VERSION_CODE, 0)
        set(value) {
            kv.encode(KEY_LAST_TELEMETRY_VERSION_CODE, value)
        }

    /** 最近一次已成功上报给远端遥测的 MaiDroid 版本名，覆盖安装热修时也会及时同步。 */
    var lastTelemetryReportedVersionName: String
        get() = kv.decodeString(KEY_LAST_TELEMETRY_VERSION_NAME, "") ?: ""
        set(value) {
            kv.encode(KEY_LAST_TELEMETRY_VERSION_NAME, value)
        }

    /** 上次已成功自动补报的崩溃记录时间戳（毫秒），成功上传后推进，避免跨进程重复上报同一场闪退。 */
    var lastAutoReportedCrashMillis: Long
        get() = kv.decodeLong(KEY_LAST_AUTO_CRASH_MILLIS, 0L)
        set(value) {
            kv.encode(KEY_LAST_AUTO_CRASH_MILLIS, value)
        }

    /** 检查今天（按 yyyy-MM-dd）的自动错误现场上报是否还在每日配额内。 */
    fun canSendAutoErrorToday(dayKey: String, maxPerDay: Int): Boolean {
        val savedDay = kv.decodeString(KEY_AUTO_ERROR_DAY, "") ?: ""
        if (savedDay != dayKey) return maxPerDay > 0
        return kv.decodeInt(KEY_AUTO_ERROR_DAY_COUNT, 0) < maxPerDay
    }

    /** 成功发出一份自动报错现场后递增今日计数，跨天时重置归 1。 */
    fun recordAutoErrorSentToday(dayKey: String) {
        val savedDay = kv.decodeString(KEY_AUTO_ERROR_DAY, "") ?: ""
        val nextCount = if (savedDay == dayKey) kv.decodeInt(KEY_AUTO_ERROR_DAY_COUNT, 0) + 1 else 1
        kv.encode(KEY_AUTO_ERROR_DAY, dayKey)
        kv.encode(KEY_AUTO_ERROR_DAY_COUNT, nextCount)
    }

    /** 判断某个错误指纹在冷却窗口内是否已经成功上报过，防止重启或重试期间反复刷同一问题。 */
    fun wasAutoErrorFingerprintReportedRecently(fingerprint: String, nowSec: Long, cooldownSec: Long): Boolean {
        val recordedAt = decodeFingerprintTimestamps()[fingerprint] ?: return false
        return nowSec >= recordedAt && nowSec - recordedAt < cooldownSec
    }

    /** 记录某个错误指纹的最近成功上报秒级时间戳，最多保留最近 MAX_TRACKED_ERROR_FINGERPRINTS 条。 */
    fun markAutoErrorFingerprintReported(fingerprint: String, nowSec: Long) {
        val cleanFp = fingerprint.trim().replace(",", "").replace(":", "")
        if (cleanFp.isEmpty()) return
        val entries = decodeFingerprintTimestamps().toMutableMap()
        entries.remove(cleanFp)
        entries[cleanFp] = nowSec
        val encoded = entries.entries
            .toList()
            .takeLast(MAX_TRACKED_ERROR_FINGERPRINTS)
            .joinToString(",") { (fp, ts) -> "$fp:$ts" }
        kv.encode(KEY_AUTO_ERROR_FPS, encoded)
    }

    private fun decodeFingerprintTimestamps(): Map<String, Long> {
        val raw = kv.decodeString(KEY_AUTO_ERROR_FPS, "").orEmpty()
        if (raw.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, Long>()
        for (item in raw.split(',')) {
            val sep = item.indexOf(':')
            if (sep <= 0) continue
            val fp = item.substring(0, sep).trim()
            val ts = item.substring(sep + 1).trim().toLongOrNull() ?: continue
            if (fp.isNotEmpty()) result[fp] = ts
        }
        return result
    }

    /** 上次从主源拉取远程清单保存的 ETag，下次请求带 If-None-Match 命中 304 省流量。 */
    var remoteManifestEtag: String
        get() = kv.decodeString(KEY_REMOTE_MANIFEST_ETAG, "") ?: ""
        set(value) {
            kv.encode(KEY_REMOTE_MANIFEST_ETAG, value)
        }

    /** 用户已手动点关闭的远程公告 ID 列表（最多保留最近 64 条）。 */
    val dismissedAnnouncementIds: Set<String>
        get() = decodeIdSet(KEY_DISMISSED_ANNOUNCEMENTS)

    fun dismissAnnouncement(id: String) {
        appendTrackedId(KEY_DISMISSED_ANNOUNCEMENTS, id)
    }

    /** 已经往系统通知栏推送过的远程公告 ID 列表，防止后台每小时轮询时重复刷同一条通知。 */
    val notifiedAnnouncementIds: Set<String>
        get() = decodeIdSet(KEY_NOTIFIED_ANNOUNCEMENTS)

    fun markAnnouncementNotified(id: String) {
        appendTrackedId(KEY_NOTIFIED_ANNOUNCEMENTS, id)
    }

    /** 读取指定分类（GitHub / Debian APT / PyPI）的镜像源选择策略、预设键值与自定义地址。 */
    override fun mirrorPreference(category: MirrorCategory): MirrorPreference {
        val (modeKey, presetKey, customKey) = mirrorKeys(category)
        val mode = MirrorSelectionMode.fromStorage(kv.decodeString(modeKey, null))
        val preset = kv.decodeString(presetKey, "") ?: ""
        val custom = kv.decodeString(customKey, "")?.trim().orEmpty()
        return MirrorPreference(mode = mode, presetValue = preset, customUrl = custom)
    }

    /** 将用户在镜像源设置页选择的模式与自定义地址实时落盘至 MMKV。 */
    override fun saveMirrorPreference(category: MirrorCategory, preference: MirrorPreference) {
        val (modeKey, presetKey, customKey) = mirrorKeys(category)
        kv.encode(modeKey, preference.mode.storageValue)
        kv.encode(presetKey, preference.presetValue)
        kv.encode(customKey, preference.customUrl.trim())
    }

    private fun mirrorKeys(category: MirrorCategory): Triple<String, String, String> = when (category) {
        MirrorCategory.GitHub -> Triple(KEY_MIRROR_GITHUB_MODE, KEY_MIRROR_GITHUB_PRESET, KEY_MIRROR_GITHUB_CUSTOM)
        MirrorCategory.Apt -> Triple(KEY_MIRROR_APT_MODE, KEY_MIRROR_APT_PRESET, KEY_MIRROR_APT_CUSTOM)
        MirrorCategory.PyPi -> Triple(KEY_MIRROR_PYPI_MODE, KEY_MIRROR_PYPI_PRESET, KEY_MIRROR_PYPI_CUSTOM)
    }

    private fun decodeIdSet(key: String): Set<String> =
        kv.decodeString(key, "")
            ?.split(',')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

    private fun appendTrackedId(key: String, id: String) {
        val clean = id.trim().replace(",", "")
        if (clean.isEmpty()) return
        val current = kv.decodeString(key, "")
            ?.split(',')
            ?.map(String::trim)
            ?.filter { it.isNotEmpty() && it != clean }
            .orEmpty()
        val updated = (current + clean).takeLast(MAX_TRACKED_ANNOUNCEMENT_IDS)
        kv.encode(key, updated.joinToString(","))
    }
}

/**
 * MMKV 的文件校验不过时怎么办。断电、存储满时设置文件可能只写进去一半，MMKV 默认的做法是整个丢掉，
 * 用户就得重新同意协议、重走一遍首次引导；改成尽量恢复：能读出来的设置都留下，读不出的那几项才丢。
 * 要在第一次读设置之前注册（MaiDroidApplication 里紧跟 MMKV.initialize）。
 */
internal object AppSettingsRecovery : MMKVHandler {
    override fun onMMKVCRCCheckFail(mmapID: String): MMKVRecoverStrategic = recover(mmapID, "校验")

    override fun onMMKVFileLengthError(mmapID: String): MMKVRecoverStrategic = recover(mmapID, "长度")

    override fun wantLogRedirecting(): Boolean = false

    override fun mmkvLog(level: MMKVLogLevel, file: String, line: Int, function: String, message: String) = Unit

    private fun recover(mmapID: String, what: String): MMKVRecoverStrategic {
        Log.w("MaiDroid", "MMKV $mmapID $what check failed, recovering")
        TerminalLog.append("[MaiDroid] 设置文件（$mmapID）${what}没通过，可能是上次断电或存储满了，已尽量恢复")
        return MMKVRecoverStrategic.OnErrorRecover
    }
}
