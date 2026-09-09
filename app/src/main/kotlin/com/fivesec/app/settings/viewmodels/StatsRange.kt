package com.fivesec.app.settings.viewmodels

import com.fivesec.app.util.DateUtil
import java.time.ZoneId

/** 统计页时间档位：自然周期（日 / 周一起的周 / 自然月 / 自然年）。 */
enum class StatsRange { DAY, WEEK, MONTH, YEAR }

/** 该档位在 [now] 时刻所属周期的起点（epoch 毫秒）。 */
fun StatsRange.startMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when (this) {
    StatsRange.DAY -> DateUtil.startOfDayMillis(now, zone)
    StatsRange.WEEK -> DateUtil.startOfWeekMillis(now, zone)
    StatsRange.MONTH -> DateUtil.startOfMonthMillis(now, zone)
    StatsRange.YEAR -> DateUtil.startOfYearMillis(now, zone)
}
