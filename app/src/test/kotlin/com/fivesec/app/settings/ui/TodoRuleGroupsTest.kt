package com.fivesec.app.settings.ui

import com.fivesec.app.domain.model.Todo
import com.fivesec.app.settings.viewmodels.TodoRow
import com.fivesec.app.util.TodoRecurrence.REPEAT_DAILY
import com.fivesec.app.util.TodoRecurrence.REPEAT_INTERVAL
import com.fivesec.app.util.TodoRecurrence.REPEAT_ONCE
import com.fivesec.app.util.TodoRecurrence.REPEAT_WEEKLY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 今日待办规则分组判定表（TodoRuleGroups 纯投影）：组序与编辑弹窗四段一致、
 * 不同星期集合/不同 N/不同有效期日各自成组、组内保传入序、脏值不丢行。
 */
class TodoRuleGroupsTest {

    private fun row(
        id: Long,
        type: Int,
        days: Int = 0,
        interval: Int = 0,
        dueDate: String = "",
    ) = TodoRow(
        todo = Todo(
            id = id,
            text = "t$id",
            repeatType = type,
            repeatDays = days,
            intervalDays = interval,
            dueDate = dueDate,
        ),
        doneToday = false,
        dueToday = false,
        dateLabel = "",
    )

    @Test
    fun `组序按编辑弹窗四段顺序每天周间隔单次`() {
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_ONCE),
                row(2, REPEAT_DAILY),
                row(3, REPEAT_INTERVAL, interval = 3),
                row(4, REPEAT_WEEKLY, days = 0b1),
            ),
        )

        assertEquals(listOf(REPEAT_DAILY, REPEAT_WEEKLY, REPEAT_INTERVAL, REPEAT_ONCE), groups.map { it.repeatType })
    }

    @Test
    fun `不同星期集合各自成组且按掩码升序`() {
        // bit0=周一 … bit6=周日（ISO 周一起点）：周一集合（1）应排在周三集合（4）前，
        // 与传入顺序（周三在前）相反——组序由键决定而非出现顺序
        val groups = TodoRuleGroups.groupByRule(
            listOf(row(1, REPEAT_WEEKLY, days = 0b100), row(2, REPEAT_WEEKLY, days = 0b1)),
        )

        assertEquals(listOf(0b1, 0b100), groups.map { it.repeatDays })
    }

    @Test
    fun `间隔组按 N 升序`() {
        val groups = TodoRuleGroups.groupByRule(
            listOf(row(1, REPEAT_INTERVAL, interval = 7), row(2, REPEAT_INTERVAL, interval = 3)),
        )

        assertEquals(listOf(3, 7), groups.map { it.intervalDays })
    }

    @Test
    fun `单次不同有效期日各自成组且按日期升序`() {
        // 020：11.9 建的两条单次分别指向 11.23/11.16 → 「单次/2026-11-16」在前（近的活先看见），
        // 与传入顺序（远的在前）相反——组序由键决定而非出现顺序
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_ONCE, dueDate = "2026-11-23"),
                row(2, REPEAT_ONCE, dueDate = "2026-11-10"),
                row(3, REPEAT_ONCE, dueDate = "2026-11-16"),
            ),
        )

        assertEquals(listOf("2026-11-10", "2026-11-16", "2026-11-23"), groups.map { it.dueDate })
        assertEquals(listOf(listOf(2L), listOf(3L), listOf(1L)), groups.map { g -> g.rows.map { it.todo.id } })
    }

    @Test
    fun `单次同有效期日合入同组保持传入序`() {
        val groups = TodoRuleGroups.groupByRule(
            listOf(row(1, REPEAT_ONCE, dueDate = "2026-11-23"), row(2, REPEAT_ONCE, dueDate = "2026-11-23")),
        )

        assertEquals(1, groups.size)
        assertEquals("2026-11-23", groups.single().dueDate)
        assertEquals(listOf(1L, 2L), groups.single().rows.map { it.todo.id })
    }

    @Test
    fun `重复类脏dueDate不拆组_分组键归一为空`() {
        // 防御：dueDate 恒空是重复类的写入不变式，脏值（理论上不可达）不按日期拆组——
        // 与「脏值不让条目凭空消失/错位」同一兜底思路
        val groups = TodoRuleGroups.groupByRule(
            listOf(row(1, REPEAT_DAILY, dueDate = "2026-11-23"), row(2, REPEAT_DAILY)),
        )

        assertEquals(1, groups.size)
        assertEquals("", groups.single().dueDate)
        assertEquals(listOf(1L, 2L), groups.single().rows.map { it.todo.id })
    }

    @Test
    fun `同规则条目合入同组且保持传入序`() {
        val groups = TodoRuleGroups.groupByRule(
            listOf(row(3, REPEAT_DAILY), row(1, REPEAT_DAILY), row(2, REPEAT_DAILY)),
        )

        assertEquals(1, groups.size)
        assertEquals(listOf(3L, 1L, 2L), groups.single().rows.map { it.todo.id })
    }

    @Test
    fun `空输入返回空分组`() {
        assertTrue(TodoRuleGroups.groupByRule(emptyList()).isEmpty())
    }

    @Test
    fun `未知类型兜底不丢行且排四类正序之后`() {
        val groups = TodoRuleGroups.groupByRule(
            listOf(row(9, type = 7), row(1, REPEAT_DAILY)),
        )

        assertEquals(listOf(REPEAT_DAILY, 7), groups.map { it.repeatType })
        assertEquals(listOf(9L), groups[1].rows.map { it.todo.id })
    }
}
