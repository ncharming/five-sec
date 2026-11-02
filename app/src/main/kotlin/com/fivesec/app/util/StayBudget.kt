package com.fivesec.app.util

/**
 * 每日停留预算判定（specs/015）：回弹层「今日累计」的展示强化口径。
 *
 * 为什么在 util 且零 Android 依赖：与 ResistRate 同层的叶子纯函数——预算是展示层强化
 * （「继续」永远可用，FR-014），判定必须与文案解耦（文案在 strings.xml）。
 * 预算语义：0 = 不设（默认）；累计 ≥ 预算即「触线」（含恰好——触线当天就要看见警示，
 * 不是等超出才见）；返回超出分钟数（触线且未超出时为 0，调用方区分文案）。
 */
object StayBudget {

    /** 预算下上界（分钟）：0=不设；上限与守护间隔同量级的大档（10 小时），防异常值。 */
    const val MAX_BUDGET_MINUTES = 600

    /** 是否设了预算（0=不设）。 */
    fun isSet(budgetMinutes: Int): Boolean = budgetMinutes > 0

    /**
     * 触线判定：未设预算或累计未达预算 → null（无警示）；
     * 累计 ≥ 预算 → 超出分钟数（恰好触线 = 0，调用方展示「已达预算」而非「超出 0 分钟」）。
     */
    fun overMinutes(todayStayMinutes: Long, budgetMinutes: Int): Long? {
        if (budgetMinutes <= 0 || todayStayMinutes < budgetMinutes) return null
        return todayStayMinutes - budgetMinutes
    }
}
