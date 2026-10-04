package com.fivesec.app.settings

import com.fivesec.app.domain.model.Todo
import com.fivesec.app.settings.viewmodels.TodoTodayStatsCalculator
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 今日任务三数口径测试（specs/008-todo-stats；2026-10 过期口径修订）：
 * 任务 = 轮到且启用（与覆盖层 D/T 同源谓词）；完成 = 其中今天已勾；过期 = 过期条目数
 * （单次 + 重复类，含停用）。允许一类受控重叠：过期的重复类今天轮到时同时计入任务与过期
 * （今天仍要做、之前确实错过了）——两数各自为真；间隔完成当天不计入任务/完成（D/T 同行为）。
 */
class TodoTodayStatsCalculatorTest {

    private val today = "2026-09-23" // 周三

    @Test
    fun `三数基本口径_轮到且启用为分母`() {
        val rows = listOf(
            Todo(id = 1, text = "每天未勾", isEnabled = true, lastCompletedDate = "", createdAt = today),
            Todo(id = 2, text = "每天已勾", isEnabled = true, lastCompletedDate = today, createdAt = today),
            Todo(id = 3, text = "已停用", isEnabled = false, lastCompletedDate = "", createdAt = today),
        )

        val stats = TodoTodayStatsCalculator.compute(rows, today)

        assertEquals(2, stats.total) // 停用不计入
        assertEquals(1, stats.completed)
        assertEquals(0, stats.expired) // 都今天创建，无「过去的轮到日」可错过
    }

    @Test
    fun `周几规则只在该日轮到`() {
        val mask = TodoRecurrence.bitOf(DayOfWeek.MONDAY) or TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY)
        val rows = listOf(Todo(id = 1, text = "周一三五", repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = mask, createdAt = "2026-09-22"))

        assertEquals(1, TodoTodayStatsCalculator.compute(rows, today).total) // 周三命中
        assertEquals(0, TodoTodayStatsCalculator.compute(rows, "2026-09-24").total) // 周四不命中
    }

    @Test
    fun `间隔条目完成当天不计入_第N天复活回分母`() {
        val rows = listOf(
            Todo(
                id = 1,
                text = "每3天",
                repeatType = TodoRecurrence.REPEAT_INTERVAL,
                intervalDays = 3,
                lastCompletedDate = today, // 今天刚完成 → 灰显期，D/T 同口径不计入
                createdAt = today,
            ),
        )

        assertEquals(0, TodoTodayStatsCalculator.compute(rows, today).total)
        assertEquals(0, TodoTodayStatsCalculator.compute(rows, today).completed)
        assertEquals(1, TodoTodayStatsCalculator.compute(rows, "2026-09-26").total) // 第 3 天复活
    }

    @Test
    fun `一次性当天计入且完成计数_过期单列_未来不计数`() {
        val rows = listOf(
            Todo(id = 1, text = "今天未做", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = today),
            Todo(id = 2, text = "今天做完", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = today, lastCompletedDate = today),
            Todo(id = 3, text = "昨天失败", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-22"),
            Todo(id = 4, text = "明天才做", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-24"),
        )

        val stats = TodoTodayStatsCalculator.compute(rows, today)

        assertEquals(2, stats.total) // 当天两条（含已完成；过期与未来不计入）
        assertEquals(1, stats.completed)
        assertEquals(1, stats.expired) // 只有昨天失败
    }

    @Test
    fun `停用的一次性过期仍计入过期数`() {
        val rows = listOf(
            Todo(id = 1, text = "停用但过期", isEnabled = false, repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-22"),
        )

        val stats = TodoTodayStatsCalculator.compute(rows, today)

        assertEquals(0, stats.total) // 停用不进分母
        assertEquals(1, stats.expired) // 007 口径：过期不看开关
    }

    @Test
    fun `重复类错过轮到日计入过期数_今天轮到的同时计入任务`() {
        // 2026-10-04 修订口径：重复类过期了就一定进过期数（与待办页过期区一致）
        val rows = listOf(
            // 每天：昨天在册未做 → 过期；今天轮到 → 也进任务分母（受控重叠：两数各自为真）
            Todo(id = 1, text = "每天错过昨天", createdAt = "2026-09-20"),
            // 每 3 天：09-18 完成 → 09-21 复活已过未做 → 过期；顺延期持续轮到 → 也进任务分母
            Todo(id = 2, text = "间隔复活日已过", repeatType = TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3, lastCompletedDate = "2026-09-18", createdAt = "2026-09-18"),
            // 每周一：09-21 错过 → 过期；今天周三不轮到 → 不进任务分母
            Todo(id = 3, text = "每周几错过周一", repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = TodoRecurrence.bitOf(DayOfWeek.MONDAY), createdAt = "2026-09-19"),
        )

        val stats = TodoTodayStatsCalculator.compute(rows, today)

        assertEquals(2, stats.total) // id1 + id2（id3 今天不轮到）
        assertEquals(0, stats.completed)
        assertEquals(3, stats.expired)
    }

    @Test
    fun `每天昨日完成跨日失效且不计过期`() {
        val rows = listOf(
            Todo(id = 1, text = "每天昨天勾过", lastCompletedDate = "2026-09-22", createdAt = "2026-09-20"),
        )

        val stats = TodoTodayStatsCalculator.compute(rows, today)

        assertEquals(1, stats.total) // 每天恒轮到
        assertEquals(0, stats.completed) // 昨天的勾选跨日失效
        assertEquals(0, stats.expired) // 昨天的轮到已兑现，没有错过
    }
}
