package com.fivesec.app.util

import com.fivesec.app.util.ReviewTrend.Trend
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReviewTrend] 判定矩阵（specs/014）：方向语义（好/坏跟指标定义）、无基线口径
 * （上期无数据或 0 → —，不伪造基线）、抵制率分母 0。
 */
class ReviewTrendTest {

    // ── 数值型：higherIsBetter=false（拦截次数，降是好） ──

    @Test
    fun `拦截次数下降是变好`() {
        assertEquals(Trend.BETTER, ReviewTrend.countTrend(current = 5, previous = 8, higherIsBetter = false))
    }

    @Test
    fun `拦截次数上升是变差`() {
        assertEquals(Trend.WORSE, ReviewTrend.countTrend(current = 9, previous = 8, higherIsBetter = false))
    }

    @Test
    fun `持平是持平`() {
        assertEquals(Trend.FLAT, ReviewTrend.countTrend(current = 8, previous = 8, higherIsBetter = false))
    }

    @Test
    fun `上期无数据是无基线`() {
        assertEquals(Trend.NO_BASELINE, ReviewTrend.countTrend(current = 3, previous = null, higherIsBetter = true))
    }

    @Test
    fun `上期为零同样无基线——本期0上期0也显示一横`() {
        assertEquals(Trend.NO_BASELINE, ReviewTrend.countTrend(current = 0, previous = 0, higherIsBetter = true))
        assertEquals(Trend.NO_BASELINE, ReviewTrend.countTrend(current = 3, previous = 0, higherIsBetter = true))
    }

    // ── 数值型：higherIsBetter=true（完成次数，升是好） ──

    @Test
    fun `完成次数上升是变好`() {
        assertEquals(Trend.BETTER, ReviewTrend.countTrend(current = 6, previous = 3, higherIsBetter = true))
    }

    @Test
    fun `完成次数下降是变差`() {
        assertEquals(Trend.WORSE, ReviewTrend.countTrend(current = 2, previous = 3, higherIsBetter = true))
    }

    // ── 平均停留（降是好，分钟粒度同数值规则） ──

    @Test
    fun `平均停留下降是变好`() {
        assertEquals(Trend.BETTER, ReviewTrend.countTrend(current = 10, previous = 25, higherIsBetter = false))
    }

    // ── 抵制率：分母 0 → null → 无基线 ──

    @Test
    fun `抵制率上升是变好`() {
        assertEquals(Trend.BETTER, ReviewTrend.rateTrend(currentRate = 60, previousRate = 25))
    }

    @Test
    fun `抵制率下降是变差`() {
        assertEquals(Trend.WORSE, ReviewTrend.rateTrend(currentRate = 25, previousRate = 60))
    }

    @Test
    fun `抵制率任一期无分母是无基线`() {
        assertEquals(Trend.NO_BASELINE, ReviewTrend.rateTrend(currentRate = null, previousRate = 50))
        assertEquals(Trend.NO_BASELINE, ReviewTrend.rateTrend(currentRate = 50, previousRate = null))
    }

    @Test
    fun `抵制率上期为0是合法基线——0也算趋势`() {
        assertEquals(Trend.BETTER, ReviewTrend.rateTrend(currentRate = 50, previousRate = 0))
    }
}
