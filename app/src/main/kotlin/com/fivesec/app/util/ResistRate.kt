package com.fivesec.app.util

import kotlin.math.roundToInt

/**
 * 抵制率（specs/011 拦截反馈三件套）：今日取消 / (今日取消 + 今日打开)，四舍五入取整百分比。
 *
 * 为什么排除 INTERRUPTED：打断是「未完成选择」，既不算抵制成功也不算放弃——不构成一次
 * 抵制机会，分母只认用户真实做出的 CANCELED/OPENED 两种选择。
 * 分母为 0（今天还没做出过选择）返回 null：无选择 ≠ 0% 抵制，调用方以「—」区分
 * （空态契约的精确表达，与「0 是有效数据」并行不悖：0% 只属于「选了、且全打开了」）。
 */
object ResistRate {

    /** 返回抵制率百分比 [0,100]；canceled + opened <= 0 时返回 null（无选择）。 */
    fun percent(canceled: Int, opened: Int): Int? {
        val denominator = canceled + opened
        if (denominator <= 0) return null
        return (canceled * 100.0 / denominator).roundToInt()
    }
}
