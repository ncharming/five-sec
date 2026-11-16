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
 * 今日待办规则分组判定表（TodoRuleGroups 纯投影）：置顶档（当天轮到的单次/每N天/每周几组，
 * 档内按覆盖层紧急度序 单次→每N天→每周）、每天恒轮到不置顶、未轮到组序与编辑弹窗四段一致、
 * 不同星期集合/不同 N/不同有效期日各自成组、组内保传入序、脏值不丢行不升位。
 */
class TodoRuleGroupsTest {

    /** 夹具日期（单次 dueDate 用；「是否轮到」由 dueToday 显式给出——与 VM 求值同源的读侧约定）。 */
    private val today = "2026-11-16"

    private fun row(
        id: Long,
        type: Int,
        days: Int = 0,
        interval: Int = 0,
        dueDate: String = "",
        dueToday: Boolean = false,
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
        dueToday = dueToday,
        dateLabel = "",
    )

    @Test
    fun `未轮到组序按编辑弹窗四段顺序每天周间隔单次`() {
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
    fun `当天单次组置顶于所有未轮到组之前`() {
        // 2026-11-16 用户拍板：有效期日=今天的单次是「今天不做就没了」的死线，提到卡首——
        // 与覆盖层卡片 overlayPriority「单次最前」同向；传入序（单次在最后）不影响置顶
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_DAILY),
                row(2, REPEAT_WEEKLY, days = 0b1),
                row(3, REPEAT_INTERVAL, interval = 3),
                row(4, REPEAT_ONCE, dueDate = today, dueToday = true),
            ),
        )

        assertEquals(
            listOf(REPEAT_ONCE, REPEAT_DAILY, REPEAT_WEEKLY, REPEAT_INTERVAL),
            groups.map { it.repeatType },
        )
        assertEquals(today, groups.first().dueDate)
    }

    @Test
    fun `每周组当天轮到置顶于每天组之前`() {
        // 同为周组：今天选中的（周一集合，2026-11-16 是周一）置顶，未选中的（周三集合）
        // 留在四段正序——置顶键是 dueToday 而非掩码大小
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_DAILY),
                row(2, REPEAT_WEEKLY, days = 0b100, dueToday = false),
                row(3, REPEAT_WEEKLY, days = 0b1, dueToday = true),
            ),
        )

        assertEquals(
            listOf(REPEAT_WEEKLY to 0b1, REPEAT_DAILY to 0, REPEAT_WEEKLY to 0b100),
            groups.map { it.repeatType to it.repeatDays },
        )
    }

    @Test
    fun `间隔组当天轮到置顶且同类型内按 N 升序`() {
        // N=3 今天复活 → 置顶；N=7 未到 → 留在四段正序（间隔档内 N 升序的档内版）
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_DAILY),
                row(2, REPEAT_INTERVAL, interval = 7, dueToday = false),
                row(3, REPEAT_INTERVAL, interval = 3, dueToday = true),
            ),
        )

        assertEquals(
            listOf(REPEAT_INTERVAL to 3, REPEAT_DAILY to 0, REPEAT_INTERVAL to 7),
            groups.map { it.repeatType to it.intervalDays },
        )
    }

    @Test
    fun `置顶档内按覆盖层紧急度序单次每N天每周`() {
        // 档内序=overlayPriority（单次→每N天→每周）：今日卡置顶头部与拦截卡片前 3 条同序同词汇表
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_WEEKLY, days = 0b1, dueToday = true),
                row(2, REPEAT_INTERVAL, interval = 3, dueToday = true),
                row(3, REPEAT_ONCE, dueDate = today, dueToday = true),
                row(4, REPEAT_DAILY, dueToday = true),
            ),
        )

        assertEquals(
            listOf(REPEAT_ONCE, REPEAT_INTERVAL, REPEAT_WEEKLY, REPEAT_DAILY),
            groups.map { it.repeatType },
        )
    }

    @Test
    fun `每天组当天轮到也不置顶`() {
        // 用户口径「除了每天之外」：每天恒轮到，置顶无信息量——照旧排在未轮到的重复类正序之首
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_DAILY, dueToday = true),
                row(2, REPEAT_WEEKLY, days = 0b1, dueToday = true),
            ),
        )

        assertEquals(listOf(REPEAT_WEEKLY, REPEAT_DAILY), groups.map { it.repeatType })
    }

    @Test
    fun `间隔组内任一行当天轮到即整组置顶`() {
        // 间隔锚点逐行不同（lastCompletedDate 不进分组键）：同 N 两行一行轮到一行未到 → 整组置顶，
        // 组内保持传入序——组是节奏的代表，今天有活就先看见
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_INTERVAL, interval = 3, dueToday = false),
                row(2, REPEAT_DAILY),
                row(3, REPEAT_INTERVAL, interval = 3, dueToday = true),
            ),
        )

        assertEquals(listOf(REPEAT_INTERVAL, REPEAT_DAILY), groups.map { it.repeatType })
        assertEquals(listOf(1L, 3L), groups.first().rows.map { it.todo.id })
    }

    @Test
    fun `当天单次置顶后未来单次仍按日期升序在单次段内`() {
        // 置顶只针对「今天轮到」：未来的单次是计划不是死线，留在单次段按日期升序
        val groups = TodoRuleGroups.groupByRule(
            listOf(
                row(1, REPEAT_DAILY),
                row(2, REPEAT_ONCE, dueDate = "2026-11-23"),
                row(3, REPEAT_ONCE, dueDate = today, dueToday = true),
                row(4, REPEAT_ONCE, dueDate = "2026-11-10"),
            ),
        )

        assertEquals(
            listOf(REPEAT_ONCE, REPEAT_DAILY, REPEAT_ONCE, REPEAT_ONCE),
            groups.map { it.repeatType },
        )
        assertEquals(
            listOf(today, "2026-11-10", "2026-11-23"),
            groups.filter { it.repeatType == REPEAT_ONCE }.map { it.dueDate },
        )
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
        // 与传入顺序（远的在前）相反——组序由键决定而非出现顺序；三行均未轮到（dueToday=false），
        // 排除置顶串扰、纯验单次段内升序
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

    @Test
    fun `未知类型当天轮到也不置顶`() {
        // 防御：未知脏类型（isDue 兜底为每天=恒轮到，dueToday 可能恒真）不升位到置顶档——
        // 与「脏值不让条目凭空消失/错位」同一兜底，脏组照旧排四类正序之后
        val groups = TodoRuleGroups.groupByRule(
            listOf(row(9, type = 7, dueToday = true), row(1, REPEAT_DAILY)),
        )

        assertEquals(listOf(REPEAT_DAILY, 7), groups.map { it.repeatType })
    }
}
