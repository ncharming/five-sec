package com.fivesec.app.settings

import com.fivesec.app.domain.model.Todo
import com.fivesec.app.settings.viewmodels.TodoTodayStatsCalculator
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 今日任务三数口径测试（specs/008-todo-stats）：
 * 任务 = 轮到且启用（与覆盖层 D/T 同源谓词）；完成 = 其中今天已勾；过期 = 一次性过期（含停用）。
 * 三个数互不重叠是契约——间隔完成当天不计入任务/完成（D/T 同行为）、停用不进分母、过期单列。
 */
class TodoTodayStatsCalculatorTest {

    private val today = "2026-09-23" // 周三

    @Test
    fun `三数基本口径_轮到且启用为分母`() {
        val rows = listOf(
            Todo(id = 1, text = "每天未勾", isEnabled = true, lastCompletedDate = ""),
            Todo(id = 2, text = "每天已勾", isEnabled = true, lastCompletedDate = today),
            Todo(id = 3, text = "已停用", isEnabled = false, lastCompletedDate = ""),
        )

        val stats = TodoTodayStatsCalculator.compute(rows, today)

        assertEquals(2, stats.total) // 停用不计入
        assertEquals(1, stats.completed)
        assertEquals(0, stats.expired)
    }

    @Test
    fun `周几规则只在该日轮到`() {
        val mask = TodoRecurrence.bitOf(DayOfWeek.MONDAY) or TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY)
        val rows = listOf(Todo(id = 1, text = "周一三五", repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = mask))

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
    fun `重复类永不进过期数_昨日完成跨日失效`() {
        val rows = listOf(
            Todo(id = 1, text = "每天昨天勾过", lastCompletedDate = "2026-09-22"),
        )

        val stats = TodoTodayStatsCalculator.compute(rows, today)

        assertEquals(1, stats.total) // 每天恒轮到
        assertEquals(0, stats.completed) // 昨天的勾选跨日失效
        assertEquals(0, stats.expired) // 重复类无过期
    }
}
