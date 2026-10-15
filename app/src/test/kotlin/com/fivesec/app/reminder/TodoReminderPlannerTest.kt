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
 * TodoReminderPlanner 判定表（specs/010；013 起多时刻 + 单次默认三时刻）：下次提醒时刻与
 * 到点过滤的纯逻辑全量口径。时钟/时区一律显式注入（CI UTC 与真机逐位一致，005 已验证的同模式）；
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
        reminderTimes: String = "18:30",
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
        reminderTimes = reminderTimes,
    )

    // ── isValidReminderTimes ──

    @Test
    fun `空串合法等于清除提醒`() {
        assertTrue(TodoReminderPlanner.isValidReminderTimes(""))
    }

    @Test
    fun `合法HHmm通过非法格式拒绝`() {
        assertTrue(TodoReminderPlanner.isValidReminderTimes("00:00"))
        assertTrue(TodoReminderPlanner.isValidReminderTimes("23:59"))
        assertTrue(TodoReminderPlanner.isValidReminderTimes("08:05"))
        assertTrue(TodoReminderPlanner.isValidReminderTimes("20:00,21:00,22:00")) // 013：多条 CSV
        assertFalse(TodoReminderPlanner.isValidReminderTimes("24:00"))
        assertFalse(TodoReminderPlanner.isValidReminderTimes("8:30")) // 非补零两位
        assertFalse(TodoReminderPlanner.isValidReminderTimes("12:60"))
        assertFalse(TodoReminderPlanner.isValidReminderTimes("12:30:00")) // 秒不入存
        assertFalse(TodoReminderPlanner.isValidReminderTimes("1830"))
        assertFalse(TodoReminderPlanner.isValidReminderTimes("20:00,25:99")) // 多条中一段非法即整串拒
        assertFalse(TodoReminderPlanner.isValidReminderTimes("20:00,,21:00")) // 空段拒绝（写入侧不迁就脏形态）
    }

    @Test
    fun `条数超上限拒绝`() {
        assertTrue(TodoReminderPlanner.isValidReminderTimes("20:00,21:00,22:00")) // 恰好 3 条
        assertFalse(TodoReminderPlanner.isValidReminderTimes("20:00,21:00,22:00,23:00")) // 4 条 > 上限 3
    }

    // ── parseReminderTimes / normalizeReminderTimes：防御读与规范形 ──

    @Test
    fun `解析去空段非法段并升序去重`() {
        assertEquals(listOf("07:00", "18:30"), TodoReminderPlanner.parseReminderTimes("18:30,07:00,18:30"))
        assertEquals(listOf("20:00", "21:00"), TodoReminderPlanner.parseReminderTimes(" 21:00 , bad ,20:00,,")) // 脏数据兜底为尽量可用
        assertEquals(emptyList<String>(), TodoReminderPlanner.parseReminderTimes(""))
    }

    @Test
    fun `归一化落规范形空列表落空串`() {
        assertEquals("07:00,18:30", TodoReminderPlanner.normalizeReminderTimes("18:30,07:00,18:30"))
        assertEquals("", TodoReminderPlanner.normalizeReminderTimes(""))
    }

    // ── timesOnRuleSwitch：切单次预填 / 离开还原（013 弹窗联动纯逻辑）──

    @Test
    fun `切到单次且无时刻预填默认三时刻`() {
        val switched = TodoReminderPlanner.timesOnRuleSwitch(
            TodoRecurrence.REPEAT_DAILY, TodoRecurrence.REPEAT_ONCE, currentRaw = "", currentAutoFilled = false,
        )
        assertEquals(TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW to true, switched)
        assertEquals("20:00,21:00,22:00", switched.first) // 当天 20/21/22 整点三连响
    }

    @Test
    fun `离开单次且预填原封未动则还原为空`() {
        val switched = TodoReminderPlanner.timesOnRuleSwitch(
            TodoRecurrence.REPEAT_ONCE, TodoRecurrence.REPEAT_WEEKLY,
            currentRaw = TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW, currentAutoFilled = true,
        )
        assertEquals("" to false, switched)
    }

    @Test
    fun `离开单次但时刻已被用户改过则原样保留`() {
        // 预填后动手（摘掉 22:00）：autoFilled 已摘，切换不吞用户输入
        val kept = TodoReminderPlanner.timesOnRuleSwitch(
            TodoRecurrence.REPEAT_ONCE, TodoRecurrence.REPEAT_DAILY, currentRaw = "20:00,21:00", currentAutoFilled = false,
        )
        assertEquals("20:00,21:00" to false, kept)
        // 形同默认但属用户重设（autoFilled=false）：同样保留——「原封未动」只看标记不碰巧合同形
        val manual = TodoReminderPlanner.timesOnRuleSwitch(
            TodoRecurrence.REPEAT_ONCE, TodoRecurrence.REPEAT_DAILY,
            currentRaw = TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW, currentAutoFilled = false,
        )
        assertEquals(TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW to false, manual)
    }

    @Test
    fun `切到单次但已有时刻不预填`() {
        val switched = TodoReminderPlanner.timesOnRuleSwitch(
            TodoRecurrence.REPEAT_DAILY, TodoRecurrence.REPEAT_ONCE, currentRaw = "07:00", currentAutoFilled = false,
        )
        assertEquals("07:00" to false, switched)
    }

    // ── nextTriggerAt：每天 ──

    @Test
    fun `每天规则时刻未过返回今天该时刻`() {
        assertEquals(at(wednesday, LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(todo(), now, zone))
    }

    @Test
    fun `每天规则时刻已过顺延明天绝不补响`() {
        val t = todo(reminderTimes = "08:00")
        assertEquals(at(wednesday.plusDays(1), LocalTime.of(8, 0)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `每天规则今天已完成静默跳到明天`() {
        val t = todo(lastCompletedDate = wednesday.toString())
        assertEquals(at(wednesday.plusDays(1), LocalTime.of(18, 30)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    // ── nextTriggerAt：多时刻（013）──

    @Test
    fun `多时刻当天取最早未来时刻`() {
        // 单次默认三时刻：now=10:00 → 今晚 20:00（最早的未过时刻，而非列表里任意固定值）
        val t = todo(
            reminderTimes = TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW,
            repeatType = TodoRecurrence.REPEAT_ONCE,
            dueDate = wednesday.toString(),
        )
        assertEquals(at(wednesday, LocalTime.of(20, 0)), TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    @Test
    fun `多时刻部分已过取剩余最早绝不整体作废`() {
        // now=20:30：20:00 已过 → 21:00（关键回归：不能「最早候选已过就整条落空」）
        val t = todo(
            reminderTimes = TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW,
            repeatType = TodoRecurrence.REPEAT_ONCE,
            dueDate = wednesday.toString(),
        )
        assertEquals(
            at(wednesday, LocalTime.of(21, 0)),
            TodoReminderPlanner.nextTriggerAt(t, at(wednesday, LocalTime.of(20, 30)), zone),
        )
    }

    @Test
    fun `多时刻当天全部已过顺延明天最早`() {
        val t = todo(reminderTimes = "08:00,09:30")
        assertEquals(
            at(wednesday.plusDays(1), LocalTime.of(8, 0)),
            TodoReminderPlanner.nextTriggerAt(t, at(wednesday, LocalTime.of(23, 0)), zone),
        )
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
        val t = todo(repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = wednesday.toString(), reminderTimes = "08:00")
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

    @Test
    fun `单次规则未来有效期日取该日最早时刻`() {
        // revive 后防御分支：dueDate > 今天（时钟回拨等脏形态）→ 该日最早时刻
        val t = todo(
            reminderTimes = "22:00,20:00",
            repeatType = TodoRecurrence.REPEAT_ONCE,
            dueDate = wednesday.plusDays(1).toString(),
        )
        assertEquals(
            at(wednesday.plusDays(1), LocalTime.of(20, 0)),
            TodoReminderPlanner.nextTriggerAt(t, now, zone),
        )
    }

    // ── nextTriggerAt：防御 ──

    @Test
    fun `停用条目不排程`() {
        assertNull(TodoReminderPlanner.nextTriggerAt(todo(isEnabled = false), now, zone))
    }

    @Test
    fun `空提醒时刻与非法时刻不排程`() {
        assertNull(TodoReminderPlanner.nextTriggerAt(todo(reminderTimes = ""), now, zone))
        assertNull(TodoReminderPlanner.nextTriggerAt(todo(reminderTimes = "25:99"), now, zone))
    }

    @Test
    fun `多段编码部分段脏防御过滤后按合法段排程`() {
        // "25:99" 被剔掉，剩 20:00/21:00 → 今天 20:00（防御读：脏数据兜底为尽量可用而非整条报废）
        assertEquals(
            at(wednesday, LocalTime.of(20, 0)),
            TodoReminderPlanner.nextTriggerAt(todo(reminderTimes = "20:00,25:99,21:00"), now, zone),
        )
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
        val todayEvening = todo(reminderTimes = "18:30")
        val tomorrowMorning = todo(reminderTimes = "07:00")
        assertEquals(
            at(wednesday, LocalTime.of(18, 30)),
            TodoReminderPlanner.nextTriggerAt(listOf(tomorrowMorning, todayEvening), now, zone),
        )
        assertNull(TodoReminderPlanner.nextTriggerAt(listOf(todo(reminderTimes = "")), now, zone))
        assertNull(TodoReminderPlanner.nextTriggerAt(emptyList(), now, zone))
    }

    @Test
    fun `全量多条目多时刻取全局最早`() {
        val onceDefaults = todo(
            reminderTimes = TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW,
            repeatType = TodoRecurrence.REPEAT_ONCE,
            dueDate = wednesday.toString(),
        )
        val dailyEarly = todo(reminderTimes = "19:00")
        assertEquals(
            at(wednesday, LocalTime.of(19, 0)),
            TodoReminderPlanner.nextTriggerAt(listOf(onceDefaults, dailyEarly), now, zone),
        )
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

    @Test
    fun `到点过滤多时刻逐一命中未列时刻不响`() {
        val t = todo(
            reminderTimes = TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW,
            repeatType = TodoRecurrence.REPEAT_ONCE,
            dueDate = wednesday.toString(),
        )
        val day = wednesday.toString()
        listOf("20:00", "21:00", "22:00").forEach { minute ->
            assertTrue(TodoReminderPlanner.isDueForMinute(t, day, minute)) // 列表内每个时刻到点都响
        }
        assertFalse(TodoReminderPlanner.isDueForMinute(t, day, "23:00")) // 未列出的时刻不响
    }

    @Test
    fun `到点过滤完成即静默列表内后续时刻全不响`() {
        // 013 验收口径：勾掉完成 → 当天 lastCompletedDate 命中 → 剩余 21:00/22:00 一个都不触发
        val t = todo(
            reminderTimes = TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES_RAW,
            repeatType = TodoRecurrence.REPEAT_ONCE,
            dueDate = wednesday.toString(),
            lastCompletedDate = wednesday.toString(),
        )
        val day = wednesday.toString()
        listOf("20:00", "21:00", "22:00").forEach { minute ->
            assertFalse(TodoReminderPlanner.isDueForMinute(t, day, minute))
        }
        // 排程侧同口径：完成后 nextTriggerAt 直接 null（无「下一响」可排）
        assertNull(TodoReminderPlanner.nextTriggerAt(t, now, zone))
    }

    companion object {
        private fun java.time.DayOfWeek.toSet(): Set<java.time.DayOfWeek> = setOf(this)
    }
}
