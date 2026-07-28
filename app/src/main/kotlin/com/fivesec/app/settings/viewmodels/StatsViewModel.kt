package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.domain.model.AppStatistics
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class StatsUi(val total: Int, val canceled: Int, val opened: Int, val streak: Int)
data class AppStatsUi(val packageName: String, val appName: String, val totalInterceptions: Long, val cancellationRate: Double, val completedExercises: Long)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val interceptionRepository: InterceptionRepository,
    timeProvider: TimeProvider,
) : ViewModel() {

    private val startOfDay = DateUtil.startOfDayMillis(timeProvider.now())
    private val today = DateUtil.todayString(timeProvider.now())

    val ui: StateFlow<StatsUi> =
        combine(
            interceptionRepository.observeStats(startOfDay),
            interceptionRepository.observeActiveDays(),
        ) { day, days ->
            StatsUi(
                total = day.total,
                canceled = day.canceled,
                opened = day.opened,
                streak = DateUtil.computeStreak(days, today),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, StatsUi(0, 0, 0, 0))

    // 应用级别统计（与目标应用列表结合使用）
    fun observeAppStatistics(packageName: String, appName: String): StateFlow<AppStatsUi> =
        interceptionRepository.observeAllAppStatistics().stateIn(viewModelScope, SharingStarted.Eagerly, AppStatsUi(packageName, appName, 0, 0.0, 0))
            .combine(
                // 这里可以添加实时更新逻辑
                interceptionRepository.observeAllAppStatistics()
            ) { baseStats, allStats ->
                val appStats = allStats.find { it.packageName == packageName }
                AppStatsUi(
                    packageName = packageName,
                    appName = appName,
                    totalInterceptions = appStats?.totalInterceptions ?: 0,
                    cancellationRate = appStats?.cancellationRate ?: 0.0,
                    completedExercises = appStats?.completedExercises ?: 0
                )
            }
}
