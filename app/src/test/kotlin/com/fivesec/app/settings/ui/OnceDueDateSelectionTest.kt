package com.fivesec.app.settings.ui

import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单次生效日可选域判定表（OnceDueDateSelection 纯逻辑，specs/020；2026-11-13 用户确认口径）：
 * **周一~周日全可选**——星期几在任何分支都不参与判定（不存在「只有工作日」限定），唯一不可选的
 * 是今天之前的日子；today 脏值防御退化为全可选。DatePicker 的 UTC 毫秒 ⇄ yyyy-MM-dd 互转往返无损。
 */
class OnceDueDateSelectionTest {

    /** 今天=周五 2026-11-13（跨多周的周一~周日都该可选——含紧邻的周六日与多月后的周六日）。 */
    private val today = LocalDate.parse("2026-11-13")
    private val todayUtc = OnceDueDateSelection.toUtcMillis(today.toString())!!

    @Test
    fun `今天起未来一整月周一到周日逐日全可选`() {
        // 覆盖连续 31 天，必然含 4+ 个周六与 4+ 个周日——若存在任何「工作日限定」此处即红
        val allDaysSelectable = (0L..31L).all { offset ->
            val day = today.plusDays(offset)
            OnceDueDateSelection.isSelectableDate(OnceDueDateSelection.toUtcMillis(day.toString())!!, todayUtc)
        }
        assertTrue(allDaysSelectable)
    }

    @Test
    fun `每个星期几的今天未来首个出现日都可选`() {
        // 从今天往前找「最近的过去周六/周日」不可选、往后找「最近的未来周六/周日」可选——
        // 直接按 DayOfWeek 逐个断言，把「全周可选」表达成逐星期几的显式判定
        DayOfWeek.values().forEach { dow ->
            val past = (1L..7L).map { today.minusDays(it) }.first { it.dayOfWeek == dow }
            val future = (1L..7L).map { today.plusDays(it) }.first { it.dayOfWeek == dow }
            assertFalse(
                "过去$dow 应不可选",
                OnceDueDateSelection.isSelectableDate(OnceDueDateSelection.toUtcMillis(past.toString())!!, todayUtc),
            )
            assertTrue(
                "未来$dow 应可选",
                OnceDueDateSelection.isSelectableDate(OnceDueDateSelection.toUtcMillis(future.toString())!!, todayUtc),
            )
        }
    }

    @Test
    fun `今天自身可选_昨天不可选`() {
        assertTrue(OnceDueDateSelection.isSelectableDate(todayUtc, todayUtc))
        val yesterdayUtc = OnceDueDateSelection.toUtcMillis(today.minusDays(1).toString())!!
        assertFalse(OnceDueDateSelection.isSelectableDate(yesterdayUtc, todayUtc))
    }

    @Test
    fun `无选中与脏值防御_未选中不可确认_今天脏值退化全可选`() {
        // selectedDateMillis=null（未选）→ 不可确认（确认按钮禁用的判定源）
        assertFalse(OnceDueDateSelection.isSelectableDate(null, todayUtc))
        // todayUtc=null（today 脏值，正常不可达）→ 宁可多放开不崩溃
        val yesterdayUtc = OnceDueDateSelection.toUtcMillis(today.minusDays(1).toString())!!
        assertTrue(OnceDueDateSelection.isSelectableDate(yesterdayUtc, null))
        assertTrue(OnceDueDateSelection.isSelectableYear(1900, null))
    }

    @Test
    fun `年份可选下界为今天的年份`() {
        assertTrue(OnceDueDateSelection.isSelectableYear(2026, todayUtc))
        assertTrue(OnceDueDateSelection.isSelectableYear(2100, todayUtc))
        assertFalse(OnceDueDateSelection.isSelectableYear(2025, todayUtc))
    }

    @Test
    fun `日期串与UTC毫秒互转往返无损`() {
        val day = LocalDate.parse("2027-02-28") // 短月边界
        assertEquals(day.toString(), OnceDueDateSelection.toDateString(OnceDueDateSelection.toUtcMillis(day.toString())!!))
        // 非法串防御：null 不抛异常（调用方走退化分支）
        assertEquals(null, OnceDueDateSelection.toUtcMillis("11/23"))
        assertEquals(null, OnceDueDateSelection.toUtcMillis(""))
    }
}
