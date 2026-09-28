package com.fivesec.app.reminder

import com.fivesec.app.domain.model.Todo
import com.fivesec.app.util.TodoRecurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TodoReminderPlanner 判定表（specs/010）：下次提醒时刻与到点过滤的纯逻辑全量口径。
 * 时钟/时区一律显式注入（CI UTC 与真机逐位一致，005 已验证的同模式）；
 * 锚点日 2026-10-14 是周三（周几用例的确定性前提）。
 */
class TodoReminderPlannerTest {

    private val zone = ZoneId.of("UTC")
    private val wednesday = LocalDate.of(2026, 10, 14) // 周三（周三+1=周四 10-15）

    /** 2026-10-14（周三）10:00 UTC。 */
    private val now = at(wednesday, LocalTime.of(10, 0))

    private fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun todo(
        reminderTime: String = "18:30",
        isEnabled: Boolean = true,
        lastCompletedDate: String = "",
        repeatType: Int = TodoRecurrence.REPEAT_DAILY,
        repeatDays: Int = 0,
        intervalDays: Int = 0,
        dueDate: String = "",
    ) = Todo(
        text = "条目",
        isEnabled = isEnabled,
        lastCompletedDate = lastCompletedDate,
        repeatType = repeatType,
        repeatDays = repeatDays,
        intervalDays = intervalDays,
        dueDate = dueDate,
        reminderTime = reminderTime,
    )

    // ── isValidReminderTime ──

    @Test
    fun `空串合法等于清除提醒`() {
        assertTrue(TodoReminderPlanner.isValidReminderTime(""))
    }

    @Test
    fun `合法HHmm通过非法格式拒绝`() {
        assertTrue(TodoReminderPlanner.isValidReminderTime("00:00"))
        assertTrue(TodoReminderPlanner.isValidReminderTime("23:59"))
        assertTrue(TodoReminderPlanner.isValidReminderTime("08:05"))
        assertFalse(TodoReminderPlanner.isValidReminderTime("24:00"))
        assertFalse(TodoReminderPlanner.isValidReminderTime("8:30")) // 非补零两位
        assertFalse(TodoReminderPlanner.isValidReminderTime("12:60"))
        assertFalse(TodoReminderPlanner.isValidReminderTime("12:30:00")) // 秒不入存
        assertFalse(TodoReminderPlanner.isValidReminderTime("1830"))
    }

    // ── nextTriggerAt：每天 ──

    @Test
    fun `每天规则时刻未过返回今天该时刻`() {
        assertEquals(at(wednesday, LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(todo(), now, zone))
    }

    @Test
    fun `每天规则时刻已过顺延明天绝不补响`() {
        val t = todo(reminderTime = "08:00")
        assertEquals(at(wednesday.plusDays(1), LocalTime.of(8, 0)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `每天规则今天已完成静默跳到明天`() {
        val t = todo(lastCompletedDate = wednesday.toString())
        assertEquals(at(wednesday.plusDays(1), LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    // ── nextTriggerAt：周几 ──

    @Test
    fun `周几规则今天命中且时刻未过返回今天`() {
        val wedMask = TodoRecurrence.weeklyMask(java.time.DayOfWeek.WEDNESDAY.toSet())
        val t = todo(repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = wedMask)
        assertEquals(at(wednesday, LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `周几规则今天不命中排到下一个命中日`() {
        val thuMask = TodoRecurrence.weeklyMask(java.time.DayOfWeek.THURSDAY.toSet())
        val t = todo(repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = thuMask)
        assertEquals(at(wednesday.plusDays(1), LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `周几规则空集脏数据不排程`() {
        val t = todo(repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = 0)
        assertNull(TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    // ── nextTriggerAt：每 N 天 ──

    @Test
    fun `间隔规则完成后第N天复活`() {
        val t = todo(
            repeatType = TodoRecurrence.REPEAT_INTERVAL,
            intervalDays = 3,
            lastCompletedDate = "2026-10-12", // 距周三 2 天 < 3：未轮到
        )
        assertEquals(at(wednesday.plusDays(1), LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `间隔规则拖延顺延期持续轮到今天连响`() {
        val t = todo(
            repeatType = TodoRecurrence.REPEAT_INTERVAL,
            intervalDays = 3,
            lastCompletedDate = "2026-10-01", // 距周三 13 天 ≥ 3：已到期，顺延期每天轮到
        )
        assertEquals(at(wednesday, LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `间隔规则从未完成恒轮到今天`() {
        val t = todo(repeatType = TodoRecurrence.REPEAT_INTERVAL, intervalDays = 5, lastCompletedDate = "")
        assertEquals(at(wednesday, LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    // ── nextTriggerAt：单次 ──

    @Test
    fun `单次规则有效期日今天时刻未过返回今天`() {
        val t = todo(repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = wednesday.toString())
        assertEquals(at(wednesday, LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `单次规则今天时刻已过落空不补`() {
        val t = todo(repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = wednesday.toString(), reminderTime = "08:00")
        assertNull(TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `单次规则过期不响`() {
        val t = todo(repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = "2026-10-13")
        assertNull(TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `单次规则今天已完成静默`() {
        val t = todo(
            repeatType = TodoRecurrence.REPEAT_ONCE,
            dueDate = wednesday.toString(),
            lastCompletedDate = wednesday.toString(),
        )
        assertNull(TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    // ── nextTriggerAt：防御 ──

    @Test
    fun `停用条目不排程`() {
        assertNull(TodoReminderPlanner.nextTriggerAt(todo(isEnabled = false), now, zone))
    }

    @Test
    fun `空提醒时刻与非法时刻不排程`() {
        assertNull(TodoReminderPlanner.nextTriggerAt(todo(reminderTime = ""), now, zone))
        assertNull(TodoReminderPlanner.nextTriggerAt(todo(reminderTime = "25:99"), now, zone))
    }

    // ── nextTriggerAt：时区与全量 ──

    @Test
    fun `时区显式参与计算跨时区日界正确`() {
        // UTC 2026-10-14T20:00 = 上海 10-15 04:00：上海口径的「今天 18:30」= UTC 10-15T10:30
        val shanghai = ZoneId.of("Asia/Shanghai")
        val nowShanghai = Instant.parse("2026-10-14T20:00:00Z").toEpochMilli()
        val expected = Instant.parse("2026-10-15T10:30:00Z").toEpochMilli()
        assertEquals(expected, TodoReminderPlanner.nextTriggerAt(todo(), nowShanghai, shanghai))
    }

    @Test
    fun `全量取最早下一响全空则无需排程`() {
        val todayEvening = todo(reminderTime = "18:30")
        val tomorrowMorning = todo(reminderTime = "07:00")
        assertEquals(
            at(wednesday, LocalTime.of(18, 30)),
            TodoReminderPlanner.nextTriggerAt(listOf(tomorrowMorning, todayEvening), now, zone),
        )
        assertNull(TodoReminderPlanner.nextTriggerAt(listOf(todo(reminderTime = "")), now, zone))
        assertNull(TodoReminderPlanner.nextTriggerAt(emptyList(), now, zone))
    }

    // ── isDueForMinute：到点过滤 ──

    @Test
    fun `到点过滤四查全过才响`() {
        val day = wednesday.toString()
        assertTrue(TodoReminderPlanner.isDueForMinute(todo(), day, "18:30"))
        assertFalse(TodoReminderPlanner.isDueForMinute(todo(), day, "07:00")) // 时刻不匹配
        assertFalse(TodoReminderPlanner.isDueForMinute(todo(isEnabled = false), day, "18:30")) // 停用
        assertFalse(
            TodoReminderPlanner.isDueForMinute(todo(lastCompletedDate = day), day, "18:30"), // 当天已完成
        )
        val thuOnly = todo(
            repeatType = TodoRecurrence.REPEAT_WEEKLY,
            repeatDays = TodoRecurrence.weeklyMask(java.time.DayOfWeek.THURSDAY.toSet()),
        )
        assertFalse(TodoReminderPlanner.isDueForMinute(thuOnly, day, "18:30")) // 今天不轮到
    }

    @Test
    fun `到点过滤单次有效期日当天才响`() {
        val t = todo(repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = wednesday.toString())
        assertTrue(TodoReminderPlanner.isDueForMinute(t, wednesday.toString(), "18:30"))
        assertFalse(TodoReminderPlanner.isDueForMinute(t, wednesday.plusDays(1).toString(), "18:30"))
    }

    companion object {
        private fun java.time.DayOfWeek.toSet(): Set<java.time.DayOfWeek> = setOf(this)
    }
}
