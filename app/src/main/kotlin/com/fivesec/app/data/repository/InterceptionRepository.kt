package com.fivesec.app.data.repository

import com.fivesec.app.data.db.AppStatisticsDao
import com.fivesec.app.data.db.InterceptionEventDao
import com.fivesec.app.domain.model.AppStatistics
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull

/** 拦截事件的读写与统计聚合（包括总体统计和应用级别统计）。 */
@Singleton
class InterceptionRepository @Inject constructor(
    private val eventDao: InterceptionEventDao,
    private val statisticsDao: AppStatisticsDao,
    private val timeProvider: TimeProvider,
) {
    suspend fun record(event: InterceptionEvent) {
        eventDao.insert(event)
        // 更新应用级别统计
        updateAppStatistics(event)
    }

    private suspend fun updateAppStatistics(event: InterceptionEvent) {
        val now = timeProvider.now()
        val currentStats = statisticsDao.getByPackage(event.packageName)

        if (currentStats == null) {
            // 创建新的统计记录
            val newStats = AppStatistics(
                packageName = event.packageName,
                totalInterceptions = 1,
                cancellations = if (event.outcome == InterceptionOutcome.CANCELED) 1 else 0,
                cancellationRate = if (event.outcome == InterceptionOutcome.CANCELED) 1.0 else 0.0,
                completedExercises = if (event.exerciseCompleted) 1 else 0,
                lastUpdated = now
            )
            statisticsDao.upsert(newStats)
        } else {
            // 更新现有统计
            val updatedStats = currentStats.copy(
                totalInterceptions = currentStats.totalInterceptions + 1,
                cancellations = currentStats.cancellations + if (event.outcome == InterceptionOutcome.CANCELED) 1 else 0,
                completedExercises = currentStats.completedExercises + if (event.exerciseCompleted) 1 else 0,
                cancellationRate = (currentStats.cancellations + if (event.outcome == InterceptionOutcome.CANCELED) 1 else 0).toDouble() / (currentStats.totalInterceptions + 1).toDouble(),
                lastUpdated = now
            )
            statisticsDao.upsert(updatedStats)
        }
    }

    suspend fun getAppStatistics(packageName: String): AppStatistics? {
        return statisticsDao.getByPackage(packageName)
    }

    fun observeAllAppStatistics(): Flow<List<AppStatistics>> = statisticsDao.observeAll()

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
