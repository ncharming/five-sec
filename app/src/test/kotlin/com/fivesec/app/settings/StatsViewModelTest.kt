package com.fivesec.app.settings

import com.fivesec.app.util.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Test

/** 测试统计的核心纯逻辑：连击计算与日期工具。 */
class StatsViewModelTest {

    @Test
    fun `今日已完成则连击从今天起算`() {
        val days = listOf("2026-07-27", "2026-07-26", "2026-07-25")
        assertEquals(3, DateUtil.computeStreak(days, "2026-07-27"))
    }

    @Test
    fun `今日未完成但昨日连续则不断连`() {
        val days = listOf("2026-07-26", "2026-07-25", "2026-07-24")
        assertEquals(3, DateUtil.computeStreak(days, "2026-07-27"))
    }

    @Test
    fun `中间断档则只计最近连续段`() {
        val days = listOf("2026-07-27", "2026-07-26", "2026-07-23")
        assertEquals(2, DateUtil.computeStreak(days, "2026-07-27"))
    }

    @Test
    fun `无任何记录连击为 0`() {
        assertEquals(0, DateUtil.computeStreak(emptyList(), "2026-07-27"))
    }

    @Test
    fun `乱序输入也能正确计算`() {
        val days = listOf("2026-07-25", "2026-07-27", "2026-07-26")
        assertEquals(3, DateUtil.computeStreak(days, "2026-07-27"))
    }
}
