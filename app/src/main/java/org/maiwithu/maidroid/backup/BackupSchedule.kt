package org.maiwithu.maidroid.backup

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_KEY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val MONTH_DAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)

/** 计算严格晚于 [nowMillis] 的下一次本地 [hour]:[minute] 自动备份毫秒时间戳。 */
internal fun nextScheduledBackupMillis(
    nowMillis: Long,
    zoneId: ZoneId,
    hour: Int,
    minute: Int
): Long {
    val safeHour = hour.coerceIn(0, 23)
    val safeMinute = minute.coerceIn(0, 59)
    val targetTime = LocalTime.of(safeHour, safeMinute)
    val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    var candidate = nowZoned.toLocalDate().atTime(targetTime).atZone(zoneId)
    // 今天的目标时刻已到或已过（或夏令时回拨导致不严格大于 now）时推迟到次日，确保不会立刻循环触发。
    if (!candidate.toInstant().toEpochMilli().let { it > nowMillis }) {
        candidate = nowZoned.toLocalDate().plusDays(1).atTime(targetTime).atZone(zoneId)
    }
    return candidate.toInstant().toEpochMilli()
}

/**
 * 判断当前是否满足每日自动备份执行条件：
 * 同一自然日内只执行一次（对照 [lastAutoBackupDayKey]），且当前本地时间已达到或超过设定时刻。
 */
internal fun shouldRunScheduledBackup(
    nowMillis: Long,
    zoneId: ZoneId,
    hour: Int,
    minute: Int,
    lastAutoBackupDayKey: String
): Boolean {
    val safeHour = hour.coerceIn(0, 23)
    val safeMinute = minute.coerceIn(0, 59)
    val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val todayKey = nowZoned.toLocalDate().format(DAY_KEY_FORMATTER)
    if (todayKey == lastAutoBackupDayKey) return false
    return !nowZoned.toLocalTime().isBefore(LocalTime.of(safeHour, safeMinute))
}

internal fun localDayKey(epochMillis: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().format(DAY_KEY_FORMATTER)

internal fun formatScheduleTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

/** 将下一次备份时间格式化为界面展示的简洁说明（今天 / 明天 / 月-日）。 */
internal fun formatNextScheduleHint(
    nowMillis: Long,
    nextMillis: Long,
    zoneId: ZoneId
): String {
    val nowDate = Instant.ofEpochMilli(nowMillis).atZone(zoneId).toLocalDate()
    val nextZoned: ZonedDateTime = Instant.ofEpochMilli(nextMillis).atZone(zoneId)
    val nextDate = nextZoned.toLocalDate()
    val timeText = formatScheduleTime(nextZoned.hour, nextZoned.minute)
    return when (nextDate) {
        nowDate -> "今天 $timeText"
        nowDate.plusDays(1) -> "明天 $timeText"
        else -> nextZoned.format(MONTH_DAY_TIME_FORMATTER)
    }
}
