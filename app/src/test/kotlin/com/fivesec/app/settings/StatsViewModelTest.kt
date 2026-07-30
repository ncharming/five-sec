package com.fivesec.app.settings

import com.fivesec.app.util.BLACK_ARGB
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.FALLBACK_BRAND_ARGB
import com.fivesec.app.util.WHITE_ARGB
import com.fivesec.app.util.onColorForBackground
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

    @Test
    fun `浅色背景选黑色前景`() {
        assertEquals(BLACK_ARGB, onColorForBackground(0xFFFFFFFF.toInt())) // 白底
        assertEquals(BLACK_ARGB, onColorForBackground(0xFFFFEB3B.toInt())) // 亮黄底
    }

    @Test
    fun `深色背景选白色前景`() {
        assertEquals(WHITE_ARGB, onColorForBackground(0xFF000000.toInt())) // 纯黑底
        assertEquals(WHITE_ARGB, onColorForBackground(0xFF1A237E.toInt())) // 深靛底
    }

    @Test
    fun `品牌色回退为健康绿`() {
        assertEquals(0xFF00A86B.toInt(), FALLBACK_BRAND_ARGB)
    }
}
