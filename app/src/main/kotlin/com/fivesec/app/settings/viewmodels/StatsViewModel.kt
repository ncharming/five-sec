package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.domain.model.AppStatistics
import com.fivesec.app.util.AppBrandColorExtractor
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.FALLBACK_BRAND_ARGB
import com.fivesec.app.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUi(val total: Int, val canceled: Int, val opened: Int, val streak: Int)
data class AppStatsUi(val packageName: String, val appName: String, val totalInterceptions: Long, val cancellationRate: Double, val completedExercises: Long)

data class AppTodayStatsUi(
    val packageName: String,
    val appName: String,
    val brandColorArgb: Int,
    val todayInterceptions: Int,
    val todayOpened: Int,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val interceptionRepository: InterceptionRepository,
    private val targetAppRepository: TargetAppRepository,
    private val brandColorExtractor: AppBrandColorExtractor,
    private val timeProvider: TimeProvider,
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

    /** 应用品牌色（ARGB），按包名；提取在后台进行，就绪后逐个回填。 */
    private val brandColors = MutableStateFlow<Map<String, Int>>(emptyMap())

    init {
        viewModelScope.launch {
            targetAppRepository.observeAll().collect { apps ->
                val missing = apps.map { it.packageName }.toSet() - brandColors.value.keys
                missing.forEach { pkg ->
                    launch {
                        val argb = brandColorExtractor.colorArgbFor(pkg)
                        brandColors.update { it + (pkg to argb) }
                    }
                }
            }
        }
    }

    /** 每个目标应用的今日统计（拦截/打开）+ 品牌色，供统计页渲染卡片。 */
    val appTodayStats: StateFlow<List<AppTodayStatsUi>> =
        combine(
            targetAppRepository.observeAll(),
            interceptionRepository.observeTodayCountsByPackage(startOfDay),
            brandColors,
        ) { targets, counts, colors ->
            val byPkg = counts.associateBy { it.packageName }
            targets.map { t ->
                val c = byPkg[t.packageName]
                AppTodayStatsUi(
                    packageName = t.packageName,
                    appName = t.appName,
                    brandColorArgb = colors[t.packageName] ?: FALLBACK_BRAND_ARGB,
                    todayInterceptions = c?.total ?: 0,
                    todayOpened = c?.opened ?: 0,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 应用级别统计（与目标应用列表结合使用）
    fun observeAppStatistics(packageName: String, appName: String): Flow<AppStatsUi> =
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
