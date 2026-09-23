package com.fivesec.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 待办"轮到"判定纯逻辑（specs/006-recurring-todos）。
 *
 * 为什么在 util 且零时钟读取：与 DateUtil 同层的叶子，被三方消费同一实现——VM（待办页灰显派生）、
 * Repository（覆盖层快照过滤）、单测（判定表）。进出均为 `DateUtil.todayString` 口径的 yyyy-MM-dd
 * 字符串，时区责任在 today 的产出方（TimeProvider + ZoneId），本文件不读系统时钟 → CI（UTC）与
 * 真机行为逐位一致（005 已验证的同模式）。
 *
 * 滚动间隔语义（用户拍板口径）：从未完成恒轮到（锚点不参与，故无需 createdAt/anchor 列）；完成后以
 * 最近完成日整日起算，第 N 天复活；到期后持续轮到直到完成——拖延只顺延、不"错过就没"。
 */
object TodoRecurrence {

    /** 规则类型：0=每天（默认，specs/005 存量即此形态）/ 1=按星期几 / 2=每 N 天。 */
    const val REPEAT_DAILY = 0
    const val REPEAT_WEEKLY = 1
    const val REPEAT_INTERVAL = 2

    /** 间隔 N 有效域：N=1 等价"每天"，由每天规则表达不重复提供；上限一年。 */
    const val MIN_INTERVAL_DAYS = 2
    const val MAX_INTERVAL_DAYS = 365

    /** DayOfWeek.value（1=周一 … 7=周日）→ 位掩码位（bit0=周一 … bit6=周日），ISO 周一起点与统计页同口径。 */
    fun bitOf(dayOfWeek: DayOfWeek): Int = 1 shl (dayOfWeek.value - 1)

    /** 周几集合 → 位掩码；空集得 0（非法形态，由调用方校验拦截，判定侧按未命中处理）。 */
    fun weeklyMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or bitOf(d) }

    /** 间隔天数越界收敛（表单与 Repository 双层共用同一口径）。 */
    fun coerceIntervalDays(days: Int): Int = days.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)

    /**
     * 今天是否轮到（惰性求值、无状态、纯内存）。
     *
     * 防御口径：today 非法 → false（宁可不提醒不崩溃）；lastCompletedDate 非空但非法 → 视同从未完成
     * （宁可多提醒）。正常路径两个日期都由 DateUtil 产出，格式恒合法——防御分支只是给脏数据兜底。
     */
    fun isDue(
        repeatType: Int,
        repeatDays: Int,
        intervalDays: Int,
        lastCompletedDate: String,
        today: String,
    ): Boolean {
        val todayDate = today.toLocalDateOrNull() ?: return false
        return when (repeatType) {
            REPEAT_WEEKLY -> {
                val mask = 1 shl (todayDate.dayOfWeek.value - 1)
                repeatDays and mask != 0
            }

            REPEAT_INTERVAL -> {
                val last = lastCompletedDate.toLocalDateOrNull()
                    ?: return true // 空/非法 = 从未完成 = 恒到期
                ChronoUnit.DAYS.between(last, todayDate) >= intervalDays.coerceAtLeast(1)
            }

            else -> true // 每天（未知类型兜底为每天，避免脏值让条目从清单里"消失"）
        }
    }

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
}
