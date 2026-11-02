package com.fivesec.app.util

/**
 * 复盘卡趋势判定（specs/014 周月复盘卡）：两期值对比 → 趋势四态。
 *
 * 为什么在 util 且零 Android 依赖：与 ResistRate/HourDistribution 同层的叶子纯函数，
 * 方向语义内聚（调用方只给数字不给规则）；「变好」按指标定义——拦截次数**降**是好、抵制率与
 * 完成次数**升**是好、平均停留**降**是好（符号颜色跟语义不跟数值方向，箭头才跟数值方向）。
 *
 * 无基线口径（用户拍板）：上期值 null（无数据）或 0（无事件——0 造不出可信基线）一律
 * [Trend.NO_BASELINE] 显示「—」，不伪造基线；抵制率任一期分母 0 同理。
 */
object ReviewTrend {

    /** 趋势四态：BETTER 变好（品牌绿）/ WORSE 变差（红）/ FLAT 持平（灰）/ NO_BASELINE 无基线（灰「—」）。 */
    enum class Trend { BETTER, WORSE, FLAT, NO_BASELINE }

    /**
     * 数值型指标（拦截次数/完成次数/平均停留分钟）：
     * [previous] 为 null 交给调用方表达「无数据」；**0 视同无基线**（本期 0 上期 0 也显示 —，
     * 不把「从 0 到 3」说成三倍变好）。[higherIsBetter] 定义该指标的「变好」方向。
     */
    fun countTrend(current: Int, previous: Int?, higherIsBetter: Boolean): Trend {
        if (previous == null || previous <= 0) return Trend.NO_BASELINE
        return when {
            current > previous -> if (higherIsBetter) Trend.BETTER else Trend.WORSE
            current < previous -> if (higherIsBetter) Trend.WORSE else Trend.BETTER
            else -> Trend.FLAT
        }
    }

    /**
     * 抵制率（百分比整数）：任一期分母 0（rate=null，ResistRate 口径）→ 无基线——
     * 「上期没做过选择」比较不出趋势。
     */
    fun rateTrend(currentRate: Int?, previousRate: Int?): Trend {
        if (currentRate == null || previousRate == null) return Trend.NO_BASELINE
        return when {
            currentRate > previousRate -> Trend.BETTER
            currentRate < previousRate -> Trend.WORSE
            else -> Trend.FLAT
        }
    }
}
