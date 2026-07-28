package com.fivesec.app.data.repository

import com.fivesec.app.data.db.InterceptionEventDao
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** 拦截事件的读写与统计聚合。 */
@Singleton
class InterceptionRepository @Inject constructor(
    private val eventDao: InterceptionEventDao,
) {
    suspend fun record(event: InterceptionEvent) = eventDao.insert(event)

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

    data class DayStats(val total: Int, val canceled: Int, val opened: Int)
}
