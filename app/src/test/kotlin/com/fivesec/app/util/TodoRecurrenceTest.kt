package com.fivesec.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TodoRecurrence 判定表测试（specs/006-recurring-todos，contracts/todo-recurrence.md）：
 * 纯 JUnit 零 Robolectric、零时钟读取——日期一律字面量，时区免疫（CI UTC 口径）。
 * 日期锚点自校验：先证明 2026-09-21 确为周一，防止日历假设错误让后续断言失真。
 */
class TodoRecurrenceTest {

    private val monday = LocalDate.parse("2026-09-21")
    private val wednesday = LocalDate.parse("2026-09-23")
    private val thursday = LocalDate.parse("2026-09-24")

    @Test
    fun `日历锚点自校验_20260921是周一`() {
        assertEquals(DayOfWeek.MONDAY, monday.dayOfWeek)
        assertEquals(DayOfWeek.WEDNESDAY, wednesday.dayOfWeek)
        assertEquals(DayOfWeek.THURSDAY, thursday.dayOfWeek)
    }

    @Test
    fun `每天规则恒轮到_与完成状态无关`() {
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_DAILY, 0, 0, "", "2026-09-23"))
        assertTrue(
            TodoRecurrence.isDue(TodoRecurrence.REPEAT_DAILY, 0, 0, "2026-09-23", "2026-09-23"),
        )
    }

    @Test
    fun `周几命中当日轮到_未命中不轮到`() {
        val mask = TodoRecurrence.bitOf(DayOfWeek.MONDAY) or
            TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY) or
            TodoRecurrence.bitOf(DayOfWeek.FRIDAY)

        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_WEEKLY, mask, 0, "", "2026-09-23"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_WEEKLY, mask, 0, "", "2026-09-24"))
    }

    @Test
    fun `周几位掩码为0防御不轮到`() {
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_WEEKLY, 0, 0, "", "2026-09-23"))
    }

    @Test
    fun `weeklyMask与bitOf口径_周日至bit6`() {
        val mask = TodoRecurrence.weeklyMask(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY))
        assertEquals(
            TodoRecurrence.bitOf(DayOfWeek.MONDAY) or TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY),
            mask,
        )
        assertEquals(64, TodoRecurrence.bitOf(DayOfWeek.SUNDAY))
    }

    @Test
    fun `间隔从未完成恒轮到`() {
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "", "2026-09-23"))
    }

    @Test
    fun `间隔完成当天即进灰显期_第N天复活`() {
        // 「每 3 天」：完成当天 0 天差、次日 1、后日 2 → 不轮到；第 3 天差值达 N → 复活
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "2026-09-23"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "2026-09-24"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "2026-09-25"))
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "2026-09-26"))
    }

    @Test
    fun `间隔到期后持续轮到不消失`() {
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "2026-09-27"))
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "2026-10-31"))
    }

    @Test
    fun `间隔时钟回拨不轮到且不崩`() {
        // last > today（用户手动回拨时钟）：差值为负 < N → 不轮到，绝不反向清完成状态
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-26", "2026-09-23"))
    }

    @Test
    fun `间隔N边界_2与365`() {
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 2, "2026-09-23", "2026-09-24"))
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 2, "2026-09-23", "2026-09-25"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 365, "2026-09-23", "2027-09-22"))
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 365, "2026-09-23", "2027-09-23"))
    }

    @Test
    fun `间隔跨月短月日期差不炸`() {
        // 01-31 → 02-03 差 3 天：LocalDate 数学换月安全（StatsRange 同款陷阱的判定侧对偶）
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-01-31", "2026-02-03"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 4, "2026-01-31", "2026-02-03"))
    }

    @Test
    fun `today非法防御返回false_宁可不提醒不崩溃`() {
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_DAILY, 0, 0, "", "not-a-date"))
    }

    @Test
    fun `lastCompletedDate非法视同从未完成_宁可多提醒`() {
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "garbage", "2026-09-23"))
    }

    @Test
    fun `intervalDays越界收敛到2与365`() {
        assertEquals(2, TodoRecurrence.coerceIntervalDays(1))
        assertEquals(365, TodoRecurrence.coerceIntervalDays(999))
        assertEquals(30, TodoRecurrence.coerceIntervalDays(30))
    }
}
