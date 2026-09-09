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

    /** 自周期起点按应用聚合的拦截/打开/取消计数（供应用级历史统计卡片使用）。 */
    fun observeCountsByPackageSince(rangeStart: Long): Flow<List<PackageRangeCount>> =
        eventDao.observeCountsByPackageSince(rangeStart)

    data class DayStats(val total: Int, val canceled: Int, val opened: Int)
}
