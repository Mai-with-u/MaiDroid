package org.maiwithu.maidroid.backup

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DAY_KEY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
private val MONTH_DAY_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA)
private const val SLOT_GRACE_WINDOW_MS = 2 * 60 * 60 * 1000L

private val WEEKDAY_SHORTS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 依据所选频率与锚定小时，计算同一自然日内的全部计划触发小时列表（按升序排列）：
 * - 每 6 小时：从 `anchorHour % 6` 起每 6 小时一次（共 4 次，例 `04:00` -> `04, 10, 16, 22`）；
 * - 每 12 小时：从 `anchorHour % 12` 起每 12 小时一次（共 2 次，例 `04:00` -> `04, 16`）；
 * - 每天 / 每周：仅锚定小时一次。
 */
internal fun dailyTriggerHours(frequency: BackupFrequency, anchorHour: Int): List<Int> {
    val safeHour = anchorHour.coerceIn(0, 23)
    return when (frequency) {
        BackupFrequency.Every6Hours -> {
            val base = safeHour % 6
            listOf(base, base + 6, base + 12, base + 18)
        }
        BackupFrequency.Every12Hours -> {
            val base = safeHour % 12
            listOf(base, base + 12)
        }
        BackupFrequency.Daily,
        BackupFrequency.Weekly -> listOf(safeHour)
    }
}

/** 计算严格晚于 [nowMillis] 的下一次自动备份本地毫秒时间戳。 */
internal fun nextScheduledBackupMillis(
    nowMillis: Long,
    zoneId: ZoneId,
    hour: Int,
    minute: Int,
    frequency: BackupFrequency = BackupFrequency.Daily,
    dayOfWeek: Int = DEFAULT_SCHEDULE_DAY_OF_WEEK
): Long {
    val safeHour = hour.coerceIn(0, 23)
    val safeMinute = minute.coerceIn(0, 59)
    val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val today = nowZoned.toLocalDate()

    return when (frequency) {
        BackupFrequency.Every6Hours,
        BackupFrequency.Every12Hours -> {
            val hours = dailyTriggerHours(frequency, safeHour)
            // 先在当天全部间隔槽位中找第一个严格晚于当前时刻的槽位；若当天已全过则跳到次日首槽。
            val todayCandidate = hours.firstNotNullOfOrNull { triggerHour ->
                val slot = today.atTime(LocalTime.of(triggerHour, safeMinute)).atZone(zoneId)
                slot.takeIf { it.toInstant().toEpochMilli() > nowMillis }
            }
            val next = todayCandidate ?: today.plusDays(1)
                .atTime(LocalTime.of(hours.first(), safeMinute))
                .atZone(zoneId)
            next.toInstant().toEpochMilli()
        }
        BackupFrequency.Daily -> {
            val targetTime = LocalTime.of(safeHour, safeMinute)
            var candidate = today.atTime(targetTime).atZone(zoneId)
            // 今天的目标时刻已到或已过（或夏令时回拨导致不严格大于 now）时推迟到次日，确保不会立刻循环触发。
            if (candidate.toInstant().toEpochMilli() <= nowMillis) {
                candidate = today.plusDays(1).atTime(targetTime).atZone(zoneId)
            }
            candidate.toInstant().toEpochMilli()
        }
        BackupFrequency.Weekly -> {
            val targetTime = LocalTime.of(safeHour, safeMinute)
            val targetDow = DayOfWeek.of(dayOfWeek.coerceIn(1, 7))
            val daysUntil = (targetDow.value - today.dayOfWeek.value + 7) % 7
            var candidate = today.plusDays(daysUntil.toLong()).atTime(targetTime).atZone(zoneId)
            if (candidate.toInstant().toEpochMilli() <= nowMillis) {
                candidate = candidate.plusWeeks(1)
            }
            candidate.toInstant().toEpochMilli()
        }
    }
}

/**
 * 找出截至 [nowMillis] 最近一个已到期且仍在本次调度有效窗口内的计划槽位：
 * - Daily：今天的目标时刻已到或已过则返回今日槽位，不跨天补跑前一天；
 * - Weekly：今天恰为目标周几且时刻已到，或前一周期刚过不久（如周日 23:30 跨午夜延迟到周一 00:30，2 小时宽限内）；
 * - Every6Hours / Every12Hours：当天已到过的最近槽位，或当天首槽未到时落在 2 小时宽限内的昨日末槽。
 */
private fun latestEligibleSlot(
    nowMillis: Long,
    zoneId: ZoneId,
    hour: Int,
    minute: Int,
    frequency: BackupFrequency,
    dayOfWeek: Int
): ZonedDateTime? {
    val safeHour = hour.coerceIn(0, 23)
    val safeMinute = minute.coerceIn(0, 59)
    val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val today: LocalDate = nowZoned.toLocalDate()

    return when (frequency) {
        BackupFrequency.Daily -> {
            val todaySlot = today.atTime(LocalTime.of(safeHour, safeMinute)).atZone(zoneId)
            todaySlot.takeIf { it.toInstant().toEpochMilli() <= nowMillis }
        }
        BackupFrequency.Weekly -> {
            val targetTime = LocalTime.of(safeHour, safeMinute)
            val targetDow = DayOfWeek.of(dayOfWeek.coerceIn(1, 7))
            val daysSince = (today.dayOfWeek.value - targetDow.value + 7) % 7
            val candidate = today.minusDays(daysSince.toLong()).atTime(targetTime).atZone(zoneId)
            val effective = if (candidate.toInstant().toEpochMilli() <= nowMillis) {
                candidate
            } else {
                candidate.minusWeeks(1)
            }
            val elapsedMs = nowMillis - effective.toInstant().toEpochMilli()
            if (effective.toLocalDate() == today || elapsedMs in 0L..SLOT_GRACE_WINDOW_MS) {
                effective
            } else {
                null
            }
        }
        BackupFrequency.Every6Hours,
        BackupFrequency.Every12Hours -> {
            val hours = dailyTriggerHours(frequency, safeHour)
            val todayLatest = hours.asReversed().firstNotNullOfOrNull { triggerHour ->
                val slot = today.atTime(LocalTime.of(triggerHour, safeMinute)).atZone(zoneId)
                slot.takeIf { it.toInstant().toEpochMilli() <= nowMillis }
            }
            if (todayLatest != null) return todayLatest

            val yesterdayLast = today.minusDays(1)
                .atTime(LocalTime.of(hours.last(), safeMinute))
                .atZone(zoneId)
            val elapsedMs = nowMillis - yesterdayLast.toInstant().toEpochMilli()
            yesterdayLast.takeIf { elapsedMs in 0L..SLOT_GRACE_WINDOW_MS }
        }
    }
}

/** 生成与频率匹配的槽位去重键，同一槽位最多自动备份一次，兼容老版 Daily 的 `yyyy-MM-dd` 键。 */
internal fun scheduleSlotKey(slotTime: ZonedDateTime, frequency: BackupFrequency): String =
    when (frequency) {
        BackupFrequency.Daily -> slotTime.toLocalDate().format(DAY_KEY_FORMATTER)
        BackupFrequency.Weekly -> "W:${slotTime.toLocalDate().format(DAY_KEY_FORMATTER)}"
        BackupFrequency.Every6Hours,
        BackupFrequency.Every12Hours ->
            "${slotTime.toLocalDate().format(DAY_KEY_FORMATTER)}@${formatScheduleTime(slotTime.hour, slotTime.minute)}"
    }

/** 返回当前生效槽位应记录的去重键；若当前尚未进入任何有效触发槽位则返回按频率兜底的当前键。 */
internal fun currentAutoBackupSlotKey(
    nowMillis: Long,
    zoneId: ZoneId,
    hour: Int,
    minute: Int,
    frequency: BackupFrequency = BackupFrequency.Daily,
    dayOfWeek: Int = DEFAULT_SCHEDULE_DAY_OF_WEEK
): String {
    val slot = latestEligibleSlot(nowMillis, zoneId, hour, minute, frequency, dayOfWeek)
        ?: Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    return scheduleSlotKey(slot, frequency)
}

/**
 * 判断当前是否满足定时自动备份执行条件：
 * 找到当前已到期的最新计划槽位，并与上次已完成的槽位键 [lastAutoBackupSlotKey] 比对，同槽位不重复执行。
 */
internal fun shouldRunScheduledBackup(
    nowMillis: Long,
    zoneId: ZoneId,
    hour: Int,
    minute: Int,
    lastAutoBackupSlotKey: String,
    frequency: BackupFrequency = BackupFrequency.Daily,
    dayOfWeek: Int = DEFAULT_SCHEDULE_DAY_OF_WEEK
): Boolean {
    val slot = latestEligibleSlot(nowMillis, zoneId, hour, minute, frequency, dayOfWeek) ?: return false
    return scheduleSlotKey(slot, frequency) != lastAutoBackupSlotKey
}

internal fun localDayKey(epochMillis: Long, zoneId: ZoneId): String =
    Instant.ofEpochMilli(epochMillis).atZone(zoneId).toLocalDate().format(DAY_KEY_FORMATTER)

internal fun formatScheduleTime(hour: Int, minute: Int): String =
    String.format(Locale.US, "%02d:%02d", hour.coerceIn(0, 23), minute.coerceIn(0, 59))

internal fun formatWeekdayShort(dayOfWeek: Int): String =
    WEEKDAY_SHORTS[(dayOfWeek.coerceIn(1, 7) - 1)]

internal fun formatWeekdayLabel(dayOfWeek: Int): String =
    "周${formatWeekdayShort(dayOfWeek)}"

internal fun formatDailyTriggerSlots(
    frequency: BackupFrequency,
    hour: Int,
    minute: Int
): String = dailyTriggerHours(frequency, hour)
    .joinToString("、") { formatScheduleTime(it, minute) }

internal fun formatScheduleRuleSummary(
    frequency: BackupFrequency,
    dayOfWeek: Int,
    hour: Int,
    minute: Int
): String {
    val timeText = formatScheduleTime(hour, minute)
    return when (frequency) {
        BackupFrequency.Every6Hours -> "每 6 小时（$timeText 起）"
        BackupFrequency.Every12Hours -> "每 12 小时（${formatDailyTriggerSlots(frequency, hour, minute)}）"
        BackupFrequency.Daily -> "每天 $timeText"
        BackupFrequency.Weekly -> "每${formatWeekdayLabel(dayOfWeek)} $timeText"
    }
}

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
