package com.fivesec.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TodoRecurrence 判定表测试（specs/006-recurring-todos + specs/007-oneoff-todos，
 * contracts 见 specs/007/data-model.md）：纯 JUnit 零 Robolectric、零时钟读取——日期一律字面量，
 * 时区免疫（CI UTC 口径）。日期锚点自校验：先证明 2026-09-21 确为周一，防止日历假设错误让后续断言失真。
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
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_DAILY, 0, 0, "", "", "2026-09-23"))
        assertTrue(
            TodoRecurrence.isDue(TodoRecurrence.REPEAT_DAILY, 0, 0, "2026-09-23", "", "2026-09-23"),
        )
    }

    @Test
    fun `周几命中当日轮到_未命中不轮到`() {
        val mask = TodoRecurrence.bitOf(DayOfWeek.MONDAY) or
            TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY) or
            TodoRecurrence.bitOf(DayOfWeek.FRIDAY)

        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_WEEKLY, mask, 0, "", "", "2026-09-23"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_WEEKLY, mask, 0, "", "", "2026-09-24"))
    }

    @Test
    fun `周几位掩码为0防御不轮到`() {
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_WEEKLY, 0, 0, "", "", "2026-09-23"))
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
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "", "", "2026-09-23"))
    }

    @Test
    fun `间隔完成当天即进灰显期_第N天复活`() {
        // 「每 3 天」：完成当天 0 天差、次日 1、后日 2 → 不轮到；第 3 天差值达 N → 复活
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "", "2026-09-23"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "", "2026-09-24"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "", "2026-09-25"))
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "", "2026-09-26"))
    }

    @Test
    fun `间隔到期后持续轮到不消失`() {
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "", "2026-09-27"))
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-23", "", "2026-10-31"))
    }

    @Test
    fun `间隔时钟回拨不轮到且不崩`() {
        // last > today（用户手动回拨时钟）：差值为负 < N → 不轮到，绝不反向清完成状态
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-09-26", "", "2026-09-23"))
    }

    @Test
    fun `间隔N边界_2与365`() {
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 2, "2026-09-23", "", "2026-09-24"))
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 2, "2026-09-23", "", "2026-09-25"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 365, "2026-09-23", "", "2027-09-22"))
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 365, "2026-09-23", "", "2027-09-23"))
    }

    @Test
    fun `间隔跨月短月日期差不炸`() {
        // 01-31 → 02-03 差 3 天：LocalDate 数学换月安全（StatsRange 同款陷阱的判定侧对偶）
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "2026-01-31", "", "2026-02-03"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 4, "2026-01-31", "", "2026-02-03"))
    }

    @Test
    fun `today非法防御返回false_宁可不提醒不崩溃`() {
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_DAILY, 0, 0, "", "", "not-a-date"))
    }

    @Test
    fun `lastCompletedDate非法视同从未完成_宁可多提醒`() {
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_INTERVAL, 0, 3, "garbage", "", "2026-09-23"))
    }

    @Test
    fun `intervalDays越界收敛到2与365`() {
        assertEquals(2, TodoRecurrence.coerceIntervalDays(1))
        assertEquals(365, TodoRecurrence.coerceIntervalDays(999))
        assertEquals(30, TodoRecurrence.coerceIntervalDays(30))
    }

    // ── 覆盖层展示优先级（用户拍板：仅今天→每N天→每周几→每天） ──

    @Test
    fun `覆盖层优先级_仅今天最高每天最低逐级递增`() {
        assertTrue(
            TodoRecurrence.overlayPriority(TodoRecurrence.REPEAT_ONCE) <
                TodoRecurrence.overlayPriority(TodoRecurrence.REPEAT_INTERVAL),
        )
        assertTrue(
            TodoRecurrence.overlayPriority(TodoRecurrence.REPEAT_INTERVAL) <
                TodoRecurrence.overlayPriority(TodoRecurrence.REPEAT_WEEKLY),
        )
        assertTrue(
            TodoRecurrence.overlayPriority(TodoRecurrence.REPEAT_WEEKLY) <
                TodoRecurrence.overlayPriority(TodoRecurrence.REPEAT_DAILY),
        )
    }

    @Test
    fun `覆盖层优先级_未知脏值与每天同档垫底不消失`() {
        // 脏值兜底与 isDue 同思路：不让条目凭空消失，只是排最后
        assertEquals(TodoRecurrence.overlayPriority(TodoRecurrence.REPEAT_DAILY), TodoRecurrence.overlayPriority(99))
    }

    // ── 下次轮到日（编辑弹窗「下次执行」展示；与 isDue 判定表互为对偶） ──

    @Test
    fun `下次轮到日_从未完成为今天`() {
        // 滚动语义：从未完成恒到期，下一次就是今天（与 isDue 空锚点分支同口径）
        assertEquals("2026-09-23", TodoRecurrence.nextIntervalDueDate("", 3, "2026-09-23"))
    }

    @Test
    fun `下次轮到日_完成后自完成日加N天`() {
        // 「每 3 天」：09-23 完成 → 09-26 复活；灰显期内查看展示同一复活日
        assertEquals("2026-09-26", TodoRecurrence.nextIntervalDueDate("2026-09-23", 3, "2026-09-23"))
        assertEquals("2026-09-26", TodoRecurrence.nextIntervalDueDate("2026-09-23", 3, "2026-09-25"))
    }

    @Test
    fun `下次轮到日_已到期顺延场景收敛为今天`() {
        // 拖延只顺延：早已过了复活日仍没做 → 下一次是今天，不回显历史复活日
        assertEquals("2026-09-30", TodoRecurrence.nextIntervalDueDate("2026-09-23", 3, "2026-09-30"))
    }

    @Test
    fun `下次轮到日_时钟回拨仍按锚点加N天不崩溃`() {
        // last > today（用户回拨时钟）：不轮到，展示锚点 + N 的未来日
        assertEquals("2026-09-29", TodoRecurrence.nextIntervalDueDate("2026-09-26", 3, "2026-09-23"))
    }

    @Test
    fun `下次轮到日_锚点非法视同从未完成为今天`() {
        assertEquals("2026-09-23", TodoRecurrence.nextIntervalDueDate("garbage", 3, "2026-09-23"))
    }

    @Test
    fun `下次轮到日_today非法返回null由UI隐藏`() {
        assertNull(TodoRecurrence.nextIntervalDueDate("2026-09-23", 3, "not-a-date"))
    }

    @Test
    fun `下次轮到日_N死值防御按1天起算`() {
        // 正常路径 N 恒为 2..365（表单与仓库双层收敛）；此处仅证死值不炸不倒退
        assertEquals("2026-09-24", TodoRecurrence.nextIntervalDueDate("2026-09-23", 0, "2026-09-23"))
    }

    // ── 仅今天（specs/007-oneoff-todos） ──

    @Test
    fun `仅今天_有效期日当天轮到`() {
        assertTrue(TodoRecurrence.isDue(TodoRecurrence.REPEAT_ONCE, 0, 0, "", "2026-09-23", "2026-09-23"))
    }

    @Test
    fun `仅今天_有效期日前一天与后一天都不轮到`() {
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_ONCE, 0, 0, "", "2026-09-23", "2026-09-22"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_ONCE, 0, 0, "", "2026-09-23", "2026-09-24"))
    }

    @Test
    fun `仅今天_dueDate空或非法防御不轮到`() {
        // 脏数据兜底：空串/垃圾串不轮到（正常路径仓库恒写合法日期）
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_ONCE, 0, 0, "", "", "2026-09-23"))
        assertFalse(TodoRecurrence.isDue(TodoRecurrence.REPEAT_ONCE, 0, 0, "", "garbage", "2026-09-23"))
    }

    @Test
    fun `仅今天_当天完成后当天仍轮到由完成态表达`() {
        // isDue 只答"轮不轮到"；完成与否由 lastCompletedDate==today 表达（与重复类同口径）
        assertTrue(
            TodoRecurrence.isDue(TodoRecurrence.REPEAT_ONCE, 0, 0, "2026-09-23", "2026-09-23", "2026-09-23"),
        )
    }

    // ── 过期判定（specs/007-oneoff-todos） ──

    @Test
    fun `过期_有效期日已过且未完成`() {
        assertTrue(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_ONCE, "2026-09-22", "", "2026-09-23"))
        assertTrue(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_ONCE, "2026-01-01", "", "2026-12-31"))
    }

    @Test
    fun `过期_有效期日当天与未来都不过期`() {
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_ONCE, "2026-09-23", "", "2026-09-23"))
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_ONCE, "2026-09-24", "", "2026-09-23")) // 时钟回拨防御
    }

    @Test
    fun `过期_已完成的一次性不算过期`() {
        // 完成待清理由仓库惰性删除收尾；过期分类只收"失败"（specs/007 拍板口径）
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_ONCE, "2026-09-22", "2026-09-22", "2026-09-23"))
    }

    @Test
    fun `过期_重复类恒不过期`() {
        // 「拖延只顺延」：错过就等下次轮到，不进过期分类（specs/007 拍板口径）
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_DAILY, "", "", "2026-09-23"))
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_WEEKLY, "", "", "2026-09-23"))
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_INTERVAL, "", "", "2026-09-23"))
    }

    @Test
    fun `过期_dueDate空或脏数据按未过期兜底`() {
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_ONCE, "", "", "2026-09-23"))
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_ONCE, "garbage", "", "2026-09-23"))
        assertFalse(TodoRecurrence.isExpired(TodoRecurrence.REPEAT_ONCE, "2026-09-22", "", "not-a-date"))
    }
}
