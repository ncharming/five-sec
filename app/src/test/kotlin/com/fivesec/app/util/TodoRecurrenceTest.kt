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

    // ── 过期判定（specs/007 单次；2026-10-04 修订：重复类错过轮到日也过期） ──
    // 签名与 lastMissedDueDate 同构，缺省值覆盖大多数用例；2026-09-23=今天（周三锚点）。

    private fun expired(
        repeatType: Int,
        repeatDays: Int = 0,
        intervalDays: Int = 0,
        lastCompletedDate: String = "",
        dueDate: String = "",
        createdAt: String = "",
        today: String = "2026-09-23",
    ): Boolean = TodoRecurrence.isExpired(
        repeatType, repeatDays, intervalDays, lastCompletedDate, dueDate, createdAt, today,
    )

    private fun missedDueDate(
        repeatType: Int,
        repeatDays: Int = 0,
        intervalDays: Int = 0,
        lastCompletedDate: String = "",
        dueDate: String = "",
        createdAt: String = "",
        today: String = "2026-09-23",
    ): String? = TodoRecurrence.lastMissedDueDate(
        repeatType, repeatDays, intervalDays, lastCompletedDate, dueDate, createdAt, today,
    )

    @Test
    fun `过期_单次有效期日已过且未完成`() {
        assertTrue(expired(TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-22"))
        assertTrue(expired(TodoRecurrence.REPEAT_ONCE, dueDate = "2026-01-01", today = "2026-12-31"))
        assertEquals("2026-09-22", missedDueDate(TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-22"))
    }

    @Test
    fun `过期_单次当天与未来都不过期`() {
        assertFalse(expired(TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-23"))
        assertFalse(expired(TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-24")) // 时钟回拨防御
    }

    @Test
    fun `过期_已完成的单次不算过期`() {
        // 完成待清理由仓库惰性删除收尾；过期分类只收"失败"（specs/007 拍板口径）
        assertFalse(expired(TodoRecurrence.REPEAT_ONCE, lastCompletedDate = "2026-09-22", dueDate = "2026-09-22"))
    }

    @Test
    fun `过期_单次dueDate空或脏数据按未过期兜底`() {
        assertFalse(expired(TodoRecurrence.REPEAT_ONCE, dueDate = ""))
        assertFalse(expired(TodoRecurrence.REPEAT_ONCE, dueDate = "garbage"))
        assertFalse(expired(TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-22", today = "not-a-date"))
    }

    @Test
    fun `过期_每天昨天未完成即过期_错过日为昨天`() {
        // 2026-10-04 修订：重复类过期了就一定显示在过期区——每天规则昨天轮到没做=失败
        assertTrue(expired(TodoRecurrence.REPEAT_DAILY, createdAt = "2026-09-20"))
        assertEquals("2026-09-22", missedDueDate(TodoRecurrence.REPEAT_DAILY, createdAt = "2026-09-20"))
    }

    @Test
    fun `过期_每天昨天完成或今天完成都不过期`() {
        // 昨天完成：昨天的轮到已兑现；今天完成：今天就是补救日（拖延只顺延留给今天的活路）
        assertFalse(expired(TodoRecurrence.REPEAT_DAILY, lastCompletedDate = "2026-09-22", createdAt = "2026-09-20"))
        assertFalse(expired(TodoRecurrence.REPEAT_DAILY, lastCompletedDate = "2026-09-23", createdAt = "2026-09-20"))
    }

    @Test
    fun `过期_每天创建当天不算过期`() {
        // 昨天条目还不存在，昨天的轮到没有发生过
        assertFalse(expired(TodoRecurrence.REPEAT_DAILY, createdAt = "2026-09-23"))
    }

    @Test
    fun `过期_每天老数据无创建日视同久已存在`() {
        // v7 前老条目 createdAt 空串：昨天的轮到真实发生过，不因缺列抹掉失败
        assertTrue(expired(TodoRecurrence.REPEAT_DAILY, createdAt = ""))
        assertEquals("2026-09-22", missedDueDate(TodoRecurrence.REPEAT_DAILY, createdAt = ""))
    }

    @Test
    fun `过期_每周几错过最近的选中日即过期`() {
        // 周一规则、今天周三：最近错过的轮到日=09-21（周一）
        val mondayOnly = TodoRecurrence.bitOf(DayOfWeek.MONDAY)
        assertTrue(expired(TodoRecurrence.REPEAT_WEEKLY, repeatDays = mondayOnly, createdAt = "2026-09-19"))
        assertEquals("2026-09-21", missedDueDate(TodoRecurrence.REPEAT_WEEKLY, repeatDays = mondayOnly, createdAt = "2026-09-19"))
    }

    @Test
    fun `过期_每周几在错过日完成过不算过期`() {
        val mondayOnly = TodoRecurrence.bitOf(DayOfWeek.MONDAY)
        assertFalse(
            expired(TodoRecurrence.REPEAT_WEEKLY, repeatDays = mondayOnly, lastCompletedDate = "2026-09-21", createdAt = "2026-09-19"),
        )
    }

    @Test
    fun `过期_每周几今天命中但上周同日错过仍过期`() {
        // 周三规则、今天周三未做：上周三（09-16）的轮到已错过——过期区记失败账，今日区今天仍可勾
        val wedOnly = TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY)
        assertTrue(expired(TodoRecurrence.REPEAT_WEEKLY, repeatDays = wedOnly, createdAt = "2026-09-15"))
        assertEquals("2026-09-16", missedDueDate(TodoRecurrence.REPEAT_WEEKLY, repeatDays = wedOnly, createdAt = "2026-09-15"))
    }

    @Test
    fun `过期_每周几创建于最近命中日之后无错过`() {
        // 周三规则、今天周三创建：上周三不存在，今天这次还没错过
        val wedOnly = TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY)
        assertFalse(expired(TodoRecurrence.REPEAT_WEEKLY, repeatDays = wedOnly, createdAt = "2026-09-23"))
    }

    @Test
    fun `过期_每周几空集永不轮到永不错过`() {
        // 脏数据防御（表单已拦空集）：不轮到就谈不上错过
        assertFalse(expired(TodoRecurrence.REPEAT_WEEKLY, repeatDays = 0, createdAt = "2026-09-01"))
    }

    @Test
    fun `过期_每N天复活日已过未完成即过期_归因计划复活日`() {
        // 09-18 完成、每 3 天：计划 09-21 复活，今天 09-23 已过仍未做 → 失败日=09-21（非 09-22）
        assertTrue(
            expired(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3, lastCompletedDate = "2026-09-18", createdAt = "2026-09-18"),
        )
        assertEquals(
            "2026-09-21",
            missedDueDate(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3, lastCompletedDate = "2026-09-18", createdAt = "2026-09-18"),
        )
    }

    @Test
    fun `过期_每N天复活日当天与未到复活日都不过期`() {
        // 09-20 完成、每 3 天 → 09-23 复活：当天=今天轮到；09-21 完成 → 09-24 才复活
        assertFalse(
            expired(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3, lastCompletedDate = "2026-09-20", createdAt = "2026-09-20"),
        )
        assertFalse(
            expired(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3, lastCompletedDate = "2026-09-21", createdAt = "2026-09-21"),
        )
    }

    @Test
    fun `过期_每N天从未完成同每天按昨天_创建当天不算`() {
        assertTrue(expired(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3, createdAt = "2026-09-19"))
        assertEquals("2026-09-22", missedDueDate(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3, createdAt = "2026-09-19"))
        assertFalse(expired(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3, createdAt = "2026-09-23"))
    }

    @Test
    fun `过期_重复类today非法与lastCompletedDate脏值防御`() {
        assertFalse(expired(TodoRecurrence.REPEAT_DAILY, createdAt = "2026-09-20", today = "not-a-date"))
        // lastCompletedDate 非法视同从未完成（宁可多警示），照常按昨天判过期
        assertTrue(expired(TodoRecurrence.REPEAT_DAILY, lastCompletedDate = "garbage", createdAt = "2026-09-20"))
    }

    @Test
    fun `过期_间隔N死值防御按1天起算不崩`() {
        // 正常路径 N 恒为 2..365（表单与仓库双层收敛）；死值不炸、与 isDue 的 coerceAtLeast(1) 同口径
        assertFalse(expired(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 0, lastCompletedDate = "2026-09-22", createdAt = "2026-09-22"))
        assertEquals(
            "2026-09-22",
            missedDueDate(TodoRecurrence.REPEAT_INTERVAL, intervalDays = 0, lastCompletedDate = "2026-09-21", createdAt = "2026-09-21"),
        )
    }
}
