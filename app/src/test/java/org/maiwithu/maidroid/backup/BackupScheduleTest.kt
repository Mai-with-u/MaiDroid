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

        assertFalse(shouldRunScheduledBackup(beforeTarget, shanghai, hour = 4, minute = 0, lastAutoBackupDayKey = ""))
        assertTrue(shouldRunScheduledBackup(atTarget, shanghai, hour = 4, minute = 0, lastAutoBackupDayKey = "2026-11-25"))
        assertFalse(shouldRunScheduledBackup(atTarget, shanghai, hour = 4, minute = 0, lastAutoBackupDayKey = "2026-11-26"))
        assertTrue(shouldRunScheduledBackup(evening, shanghai, hour = 4, minute = 0, lastAutoBackupDayKey = ""))
        assertFalse(shouldRunScheduledBackup(evening, shanghai, hour = 4, minute = 0, lastAutoBackupDayKey = "2026-11-26"))
    }

    @Test
    fun `formatScheduleTime and formatNextScheduleHint produce concise labels`() {
        assertEquals("04:00", formatScheduleTime(4, 0))
        assertEquals("23:05", formatScheduleTime(23, 5))

        val now = ZonedDateTime.of(2026, 11, 26, 10, 0, 0, 0, shanghai).toInstant().toEpochMilli()
        val todayLater = ZonedDateTime.of(2026, 11, 26, 22, 15, 0, 0, shanghai).toInstant().toEpochMilli()
        val tomorrowMorning = ZonedDateTime.of(2026, 11, 27, 4, 0, 0, 0, shanghai).toInstant().toEpochMilli()

        assertEquals("今天 22:15", formatNextScheduleHint(now, todayLater, shanghai))
        assertEquals("明天 04:00", formatNextScheduleHint(now, tomorrowMorning, shanghai))
    }
}
