package com.fivesec.app.data.repository

import com.fivesec.app.data.db.InterceptionEventDao
import com.fivesec.app.data.db.PackageRangeCount
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 拦截事件的写入与统计聚合（事件流水为唯一数据源，只增不删）。 */
@Singleton
class InterceptionRepository @Inject constructor(
    private val eventDao: InterceptionEventDao,
) {
    /** 记录一次拦截事件。事件表只增不删，所有统计均实时聚合。 */
    suspend fun record(event: InterceptionEvent) {
        eventDao.insert(event)
    }

    fun observeStats(startOfDay: Long): Flow<DayStats> {
        return combine(
            eventDao.observeTodayCount(startOfDay),
            eventDao.observeTodayCountByOutcome(startOfDay, InterceptionOutcome.CANCELED),
            eventDao.observeTodayCountByOutcome(startOfDay, InterceptionOutcome.OPENED),
        ) { total, canceled, opened ->
            DayStats(total = total, canceled = canceled, opened = opened)
        }
    }

    fun observeActiveDays(): Flow<List<String>> = eventDao.observeActiveDays()

    /** 周期内按应用聚合的拦截/打开/取消计数（供应用级历史统计卡片使用）。 */
    fun observeCountsByPackageBetween(rangeStart: Long, rangeEnd: Long): Flow<List<PackageRangeCount>> =
        eventDao.observeCountsByPackageBetween(rangeStart, rangeEnd)

    /** 最早拦截事件时间，用于推算年份筛选下界。 */
    fun observeEarliestTimestamp(): Flow<Long?> = eventDao.observeEarliestTimestamp()

    data class DayStats(val total: Int, val canceled: Int, val opened: Int)
}
