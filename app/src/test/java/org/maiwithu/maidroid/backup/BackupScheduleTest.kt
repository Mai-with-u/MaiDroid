package org.maiwithu.maidroid.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupScheduleTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `nextScheduledBackupMillis picks today when target time has not passed`() {
        val now = ZonedDateTime.of(2026, 11, 26, 3, 59, 59, 0, shanghai).toInstant().toEpochMilli()
        val expected = ZonedDateTime.of(2026, 11, 26, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()

        assertEquals(expected, nextScheduledBackupMillis(now, shanghai, hour = 4, minute = 0))
    }

    @Test
    fun `nextScheduledBackupMillis advances to tomorrow at exact or past target time`() {
        val exact = ZonedDateTime.of(2026, 11, 26, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val later = ZonedDateTime.of(2026, 11, 26, 15, 30, 0, 0, shanghai).toInstant().toEpochMilli()
        val expectedTomorrow = ZonedDateTime.of(2026, 11, 27, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()

        assertEquals(expectedTomorrow, nextScheduledBackupMillis(exact, shanghai, hour = 4, minute = 0))
        assertEquals(expectedTomorrow, nextScheduledBackupMillis(later, shanghai, hour = 4, minute = 0))
    }

    @Test
    fun `shouldRunScheduledBackup triggers once per day after schedule time`() {
        val beforeTarget = ZonedDateTime.of(2026, 11, 26, 3, 59, 0, 0, shanghai).toInstant().toEpochMilli()
        val atTarget = ZonedDateTime.of(2026, 11, 26, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val evening = ZonedDateTime.of(2026, 11, 26, 20, 0, 0, 0, shanghai).toInstant().toEpochMilli()

        assertFalse(shouldRunScheduledBackup(beforeTarget, shanghai, hour = 4, minute = 0, lastAutoBackupSlotKey = ""))
        assertTrue(shouldRunScheduledBackup(atTarget, shanghai, hour = 4, minute = 0, lastAutoBackupSlotKey = "2026-11-25"))
        assertFalse(shouldRunScheduledBackup(atTarget, shanghai, hour = 4, minute = 0, lastAutoBackupSlotKey = "2026-11-26"))
        assertTrue(shouldRunScheduledBackup(evening, shanghai, hour = 4, minute = 0, lastAutoBackupSlotKey = ""))
        assertFalse(shouldRunScheduledBackup(evening, shanghai, hour = 4, minute = 0, lastAutoBackupSlotKey = "2026-11-26"))
    }

    @Test
    fun `interval frequencies compute multi-daily slots and deduplicate per slot`() {
        assertEquals(listOf(4, 10, 16, 22), dailyTriggerHours(BackupFrequency.Every6Hours, 4))
        assertEquals(listOf(5, 11, 17, 23), dailyTriggerHours(BackupFrequency.Every6Hours, 23))
        assertEquals(listOf(4, 16), dailyTriggerHours(BackupFrequency.Every12Hours, 4))
        assertEquals(listOf(4, 16), dailyTriggerHours(BackupFrequency.Every12Hours, 16))

        // 每 12 小时（04:00、16:00）：10:00 归属 04:00 槽位，下一个计划为今天 16:00；过了 16:00 则同日再次触发。
        val midMorning = ZonedDateTime.of(2026, 11, 26, 10, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val slot16 = ZonedDateTime.of(2026, 11, 26, 16, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val after16 = ZonedDateTime.of(2026, 11, 26, 16, 5, 0, 0, shanghai).toInstant().toEpochMilli()
        val lateNight = ZonedDateTime.of(2026, 11, 26, 23, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val nextDay04 = ZonedDateTime.of(2026, 11, 27, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()

        assertEquals(
            slot16,
            nextScheduledBackupMillis(midMorning, shanghai, hour = 4, minute = 0, frequency = BackupFrequency.Every12Hours)
        )
        assertEquals(
            nextDay04,
            nextScheduledBackupMillis(lateNight, shanghai, hour = 4, minute = 0, frequency = BackupFrequency.Every12Hours)
        )
        assertTrue(
            shouldRunScheduledBackup(
                midMorning,
                shanghai,
                hour = 4,
                minute = 0,
                lastAutoBackupSlotKey = "",
                frequency = BackupFrequency.Every12Hours
            )
        )
        val morningKey = currentAutoBackupSlotKey(
            midMorning,
            shanghai,
            hour = 4,
            minute = 0,
            frequency = BackupFrequency.Every12Hours
        )
        assertEquals("2026-11-26@04:00", morningKey)
        assertFalse(
            shouldRunScheduledBackup(
                midMorning,
                shanghai,
                hour = 4,
                minute = 0,
                lastAutoBackupSlotKey = morningKey,
                frequency = BackupFrequency.Every12Hours
            )
        )
        assertTrue(
            shouldRunScheduledBackup(
                after16,
                shanghai,
                hour = 4,
                minute = 0,
                lastAutoBackupSlotKey = morningKey,
                frequency = BackupFrequency.Every12Hours
            )
        )
    }

    @Test
    fun `weekly frequency schedules target weekday and respects grace window`() {
        // 2026-11-26 是周四，目标周日 04:00 -> 下一次为 2026-11-29 04:00
        val thursday = ZonedDateTime.of(2026, 11, 26, 10, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val sunday04 = ZonedDateTime.of(2026, 11, 29, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val nextSunday04 = ZonedDateTime.of(2026, 12, 6, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()

        assertEquals(
            sunday04,
            nextScheduledBackupMillis(thursday, shanghai, hour = 4, minute = 0, frequency = BackupFrequency.Weekly, dayOfWeek = 7)
        )
        assertEquals(
            nextSunday04,
            nextScheduledBackupMillis(sunday04, shanghai, hour = 4, minute = 0, frequency = BackupFrequency.Weekly, dayOfWeek = 7)
        )

        assertFalse(
            shouldRunScheduledBackup(thursday, shanghai, hour = 4, minute = 0, lastAutoBackupSlotKey = "", frequency = BackupFrequency.Weekly, dayOfWeek = 7)
        )
        assertTrue(
            shouldRunScheduledBackup(sunday04, shanghai, hour = 4, minute = 0, lastAutoBackupSlotKey = "", frequency = BackupFrequency.Weekly, dayOfWeek = 7)
        )
        assertFalse(
            shouldRunScheduledBackup(
                sunday04,
                shanghai,
                hour = 4,
                minute = 0,
                lastAutoBackupSlotKey = "W:2026-11-29",
                frequency = BackupFrequency.Weekly,
                dayOfWeek = 7
            )
        )

        // 周日 23:30 的计划延迟到周一 00:30（1 小时内，处于 2 小时宽限期）仍可补执行，且记在周日槽位键上。
        val mondayGrace = ZonedDateTime.of(2026, 11, 30, 0, 30, 0, 0, shanghai).toInstant().toEpochMilli()
        assertTrue(
            shouldRunScheduledBackup(
                mondayGrace,
                shanghai,
                hour = 23,
                minute = 30,
                lastAutoBackupSlotKey = "",
                frequency = BackupFrequency.Weekly,
                dayOfWeek = 7
            )
        )
        assertEquals(
            "W:2026-11-29",
            currentAutoBackupSlotKey(mondayGrace, shanghai, hour = 23, minute = 30, frequency = BackupFrequency.Weekly, dayOfWeek = 7)
        )
        assertFalse(
            shouldRunScheduledBackup(
                mondayGrace,
                shanghai,
                hour = 23,
                minute = 30,
                lastAutoBackupSlotKey = "W:2026-11-29",
                frequency = BackupFrequency.Weekly,
                dayOfWeek = 7
            )
        )
    }

    @Test
    fun `format helpers produce readable frequency and schedule hints`() {
        assertEquals("04:00", formatScheduleTime(4, 0))
        assertEquals("23:05", formatScheduleTime(23, 5))
        assertEquals("04:00、10:00、16:00、22:00", formatDailyTriggerSlots(BackupFrequency.Every6Hours, 4, 0))
        assertEquals("04:00、16:00", formatDailyTriggerSlots(BackupFrequency.Every12Hours, 4, 0))
        assertEquals("每天 04:00", formatScheduleRuleSummary(BackupFrequency.Daily, 7, 4, 0))
        assertEquals("每周日 04:00", formatScheduleRuleSummary(BackupFrequency.Weekly, 7, 4, 0))
        assertEquals("每 12 小时（04:00、16:00）", formatScheduleRuleSummary(BackupFrequency.Every12Hours, 7, 4, 0))
        assertEquals("每 6 小时（04:00 起）", formatScheduleRuleSummary(BackupFrequency.Every6Hours, 7, 4, 0))

        val now = ZonedDateTime.of(2026, 11, 26, 10, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val todayLater = ZonedDateTime.of(2026, 11, 26, 22, 15, 0, 0, shanghai).toInstant().toEpochMilli()
        val tomorrowMorning = ZonedDateTime.of(2026, 11, 27, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()

        assertEquals("今天 22:15", formatNextScheduleHint(now, todayLater, shanghai))
        assertEquals("明天 04:00", formatNextScheduleHint(now, tomorrowMorning, shanghai))
    }
}
