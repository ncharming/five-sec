package com.fivesec.app.settings

import com.fivesec.app.settings.viewmodels.StatsRange
import com.fivesec.app.settings.viewmodels.availablePeriods
import com.fivesec.app.settings.viewmodels.currentPeriod
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 周期筛选窗口边界验证：不含未来周期，周支持跨年，月限定当年。 */
class StatsRangeTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    private fun millis(date: String, time: String = "12:00"): Long =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant().toEpochMilli()

    @Test
    fun `日档只有今日且上界为明天零点`() {
        val periods = StatsRange.DAY.availablePeriods(millis("2026-09-09"), zone)

        assertEquals(1, periods.size)
        assertEquals(millis("2026-09-09", "00:00"), periods.single().startMillis)
        assertEquals(millis("2026-09-10", "00:00"), periods.single().endMillis)
    }

    @Test
    fun `周档只包含本周和上周且上界不含未来`() {
        val periods = StatsRange.WEEK.availablePeriods(millis("2026-09-09"), zone)

        assertEquals(2, periods.size)
        assertEquals(millis("2026-09-07", "00:00"), periods[0].startMillis)
        assertEquals(millis("2026-09-14", "00:00"), periods[0].endMillis)
        assertEquals(millis("2026-08-31", "00:00"), periods[1].startMillis)
        assertEquals(millis("2026-09-07", "00:00"), periods[1].endMillis)
        assertTrue(periods[0].isCurrent)
    }

    @Test
    fun `周档跨年时上周仍从上一年末周一起算`() {
        val periods = StatsRange.WEEK.availablePeriods(millis("2026-01-01"), zone)

        assertEquals(millis("2025-12-29", "00:00"), periods[0].startMillis)
        assertEquals(millis("2025-12-22", "00:00"), periods[1].startMillis)
        assertEquals(millis("2025-12-29", "00:00"), periods[1].endMillis)
    }

    @Test
    fun `月档从当前月递减到当年一月`() {
        val periods = StatsRange.MONTH.availablePeriods(millis("2026-09-09"), zone)

        assertEquals(listOf(9, 8, 7, 6, 5, 4, 3, 2, 1), periods.map { it.month })
        assertEquals(millis("2026-09-01", "00:00"), periods.first().startMillis)
        assertEquals(millis("2026-10-01", "00:00"), periods.first().endMillis)
        assertEquals(millis("2026-01-01", "00:00"), periods.last().startMillis)
        assertEquals(millis("2026-02-01", "00:00"), periods.last().endMillis)
    }

    @Test
    fun `一月时月档不产生未来月份`() {
        val periods = StatsRange.MONTH.availablePeriods(millis("2026-01-31"), zone)

        assertEquals(1, periods.size)
        assertEquals(1, periods.single().month)
        assertEquals(millis("2026-02-01", "00:00"), periods.single().endMillis)
    }

    @Test
    fun `三十一号生成短月筛选时不抛异常`() {
        val periods = StatsRange.MONTH.availablePeriods(millis("2026-12-31"), zone)

        assertEquals((12 downTo 1).toList(), periods.map { it.month })
        assertEquals(millis("2026-02-01", "00:00"), periods.first { it.month == 2 }.startMillis)
    }

    @Test
    fun `年档从最早事件年份到当前年份且不含下一年`() {
        val now = millis("2026-09-09")
        val periods = StatsRange.YEAR.availablePeriods(now, zone, millis("2024-03-15"))

        assertEquals(listOf(2026, 2025, 2024), periods.map { it.year })
        assertEquals(millis("2026-01-01", "00:00"), periods.first().startMillis)
        assertEquals(millis("2027-01-01", "00:00"), periods.first().endMillis)
        assertEquals(millis("2024-01-01", "00:00"), periods.last().startMillis)
        assertEquals(millis("2025-01-01", "00:00"), periods.last().endMillis)
    }

    @Test
    fun `没有历史事件时年档只包含当前年`() {
        val periods = StatsRange.YEAR.availablePeriods(millis("2026-09-09"), zone, null)

        assertEquals(listOf(2026), periods.map { it.year })
    }

    @Test
    fun `当前周期返回各档最新可选窗口`() {
        val now = millis("2026-09-09")

        assertEquals(millis("2026-09-09", "00:00"), StatsRange.DAY.currentPeriod(now, zone).startMillis)
        assertEquals(millis("2026-09-07", "00:00"), StatsRange.WEEK.currentPeriod(now, zone).startMillis)
        assertEquals(millis("2026-09-01", "00:00"), StatsRange.MONTH.currentPeriod(now, zone).startMillis)
        assertEquals(millis("2026-01-01", "00:00"), StatsRange.YEAR.currentPeriod(now, zone).startMillis)
    }
}
