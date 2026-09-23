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

    // ── 日期串 ↔ 毫秒换算（specs/008-todo-stats：完成事件日期与周期毫秒统一量纲） ──

    @Test
    fun `日期串转毫秒取当日零点`() {
        assertEquals(millis("2026-09-23", "00:00"), DateUtil.dateStringToMillis("2026-09-23", zone))
    }

    @Test
    fun `毫秒转日期串按指定时区归日`() {
        // 2026-09-23 01:00 +08:00 == 2026-09-22 17:00 UTC：两时区归到不同自然日
        val now = millis("2026-09-23", "01:00")
        assertEquals("2026-09-23", DateUtil.millisToDateString(now, zone))
        assertEquals("2026-09-22", DateUtil.millisToDateString(now, ZoneId.of("UTC")))
    }

    @Test
    fun `日界毫秒往返无损`() {
        val dayStart = millis("2026-09-01", "00:00")
        assertEquals("2026-09-01", DateUtil.millisToDateString(dayStart, zone))
        assertEquals(dayStart, DateUtil.dateStringToMillis(DateUtil.millisToDateString(dayStart, zone), zone))
    }

    @Test
    fun `日期串非法或空返回null不抛异常`() {
        assertEquals(null, DateUtil.dateStringToMillis(null, zone))
        assertEquals(null, DateUtil.dateStringToMillis("", zone))
        assertEquals(null, DateUtil.dateStringToMillis("2026/09/23", zone))
        assertEquals(null, DateUtil.dateStringToMillis("garbage", zone))
    }
}
