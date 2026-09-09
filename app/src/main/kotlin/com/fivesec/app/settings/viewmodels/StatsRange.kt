package com.fivesec.app.settings.viewmodels

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 统计页时间档位：自然周期（日 / 周一起的周 / 自然月 / 自然年）。 */
enum class StatsRange { DAY, WEEK, MONTH, YEAR }

data class StatsPeriod(
    val range: StatsRange,
    val startMillis: Long,
    val endMillis: Long,
    val month: Int? = null,
    val year: Int? = null,
    val isCurrent: Boolean = false,
)

fun StatsRange.currentPeriod(now: Long, zone: ZoneId = ZoneId.systemDefault()): StatsPeriod =
    availablePeriods(now, zone).first()

/** 当前档位可选的自然周期，从最新到最早；不包含未来周期。 */
fun StatsRange.availablePeriods(
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    earliestEventMillis: Long? = null,
): List<StatsPeriod> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()

    fun period(
        start: LocalDate,
        end: LocalDate,
        month: Int? = null,
        year: Int? = null,
        isCurrent: Boolean = false,
    ) = StatsPeriod(
        range = this,
        startMillis = start.atStartOfDay(zone).toInstant().toEpochMilli(),
        endMillis = end.atStartOfDay(zone).toInstant().toEpochMilli(),
        month = month,
        year = year,
        isCurrent = isCurrent,
    )

    return when (this) {
        StatsRange.DAY -> listOf(period(today, today.plusDays(1), isCurrent = true))
        StatsRange.WEEK -> {
            val currentWeek = today.with(DayOfWeek.MONDAY)
            listOf(
                period(currentWeek, currentWeek.plusWeeks(1), isCurrent = true),
                period(currentWeek.minusWeeks(1), currentWeek),
            )
        }
        StatsRange.MONTH -> (today.monthValue downTo 1).map { month ->
            // 先收敛到 1 号再换月份，避免 31 号生成 2 月等短月时抛异常。
            val start = today.withDayOfMonth(1).withMonth(month)
            period(start, start.plusMonths(1), month = month, isCurrent = month == today.monthValue)
        }
        StatsRange.YEAR -> {
            val currentYear = today.year
            val earliestYear = earliestEventMillis
                ?.let { Instant.ofEpochMilli(it).atZone(zone).year }
                ?.coerceAtMost(currentYear)
                ?: currentYear
            (currentYear downTo earliestYear).map { year ->
                val start = LocalDate.of(year, 1, 1)
                period(start, start.plusYears(1), year = year, isCurrent = year == currentYear)
            }
        }
    }
}
