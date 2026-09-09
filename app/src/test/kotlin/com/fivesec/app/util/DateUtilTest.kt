package com.fivesec.app.util

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** 周期起点纯函数的边界验证（固定 Asia/Shanghai 时区，含跨年/周日/时区参数）。 */
class DateUtilTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millis(date: String, time: String = "12:00", zoneId: ZoneId = zone): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zoneId).toInstant().toEpochMilli()

    @Test
    fun `周一起点周一输入返回当天零点`() {
        assertEquals(millis("2026-09-07", "00:00"), DateUtil.startOfWeekMillis(millis("2026-09-07", "08:30"), zone))
    }

    @Test
    fun `周日输入返回当期周一而非上周`() {
        // 2026-09-06 是周日，属 08-31（周一）开始的当期周
        assertEquals(millis("2026-08-31", "00:00"), DateUtil.startOfWeekMillis(millis("2026-09-06", "23:59"), zone))
    }

    @Test
    fun `周中输入返回本周周一`() {
        // 2026-09-09 是周三
        assertEquals(millis("2026-09-07", "00:00"), DateUtil.startOfWeekMillis(millis("2026-09-09"), zone))
    }

    @Test
    fun `跨年周返回上一年末的周一`() {
        // 2026-01-01 是周四，属 2025-12-29（周一）开始的周
        assertEquals(millis("2025-12-29", "00:00"), DateUtil.startOfWeekMillis(millis("2026-01-01"), zone))
    }

    @Test
    fun `月起点返回本月一号零点`() {
        assertEquals(millis("2026-09-01", "00:00"), DateUtil.startOfMonthMillis(millis("2026-09-09"), zone))
    }

    @Test
    fun `一号零点输入月起点等于自身`() {
        val now = millis("2026-09-01", "00:00")
        assertEquals(now, DateUtil.startOfMonthMillis(now, zone))
    }

    @Test
    fun `年起点返回一月一日零点`() {
        assertEquals(millis("2026-01-01", "00:00"), DateUtil.startOfYearMillis(millis("2026-09-09"), zone))
    }

    @Test
    fun `元旦零点输入年起点等于自身`() {
        val now = millis("2027-01-01", "00:00")
        assertEquals(now, DateUtil.startOfYearMillis(now, zone))
    }

    @Test
    fun `显式时区参与边界计算`() {
        // 2026-09-09 01:00 +08:00 == 2026-09-08 17:00 UTC
        val now = millis("2026-09-09", "01:00")
        assertEquals(millis("2026-09-09", "00:00"), DateUtil.startOfDayMillis(now, zone))
        assertEquals(
            millis("2026-09-08", "00:00", ZoneId.of("UTC")),
            DateUtil.startOfDayMillis(now, ZoneId.of("UTC")),
        )
    }
}
