package com.fivesec.app.util

import java.time.Instant
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

object DateUtil {

    fun startOfDayMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** 本自然周起点（周一 00:00，ISO 周；周日输入返回当期周一）。 */
    fun startOfWeekMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .with(DayOfWeek.MONDAY)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** 本自然月起点（1 号 00:00）。 */
    fun startOfMonthMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .withDayOfMonth(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    /** 本自然年起点（1 月 1 日 00:00）。 */
    fun startOfYearMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .withDayOfYear(1)
            .atStartOfDay(zone).toInstant().toEpochMilli()

    fun todayString(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

    /**
     * "yyyy-MM-dd" → 当日 00:00 的 epoch 毫秒（stats/008：完成事件最早日期与拦截最早事件统一量纲）。
     * 非法/空串返回 null（无数据语义），不抛异常——调用方拿 null 走"无记录"分支。
     */
    fun dateStringToMillis(date: String?, zone: ZoneId = ZoneId.systemDefault()): Long? =
        date?.let { runCatching { LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull() }

    /** epoch 毫秒 → "yyyy-MM-dd"（stats/008：StatsPeriod 的半开区间端点转日期串查完成事件表）。
     *  输入是 LocalDate.atStartOfDay 生成的日界毫秒，往返无损。 */
    fun millisToDateString(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()

    /**
     * 连续完成锻炼的天数。[activeDaysDesc] 为按日期降序的 "yyyy-MM-dd" 列表。
     * 若今天未完成但此前连续，则从昨天起算（不因尚未完成今日而断连）。
     */
    fun computeStreak(activeDaysDesc: List<String>, today: String): Int {
        val active = activeDaysDesc.toHashSet()
        var cursor = runCatching { LocalDate.parse(today) }.getOrNull() ?: return 0
        if (today !in active) cursor = cursor.minusDays(1)
        var streak = 0
        while (cursor.toString() in active) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }
}
