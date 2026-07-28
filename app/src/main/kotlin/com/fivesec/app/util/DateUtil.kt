package com.fivesec.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object DateUtil {

    fun startOfDayMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    fun todayString(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()

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
