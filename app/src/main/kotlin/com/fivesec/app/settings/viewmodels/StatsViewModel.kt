package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.util.AppBrandColorExtractor
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.FALLBACK_BRAND_ARGB
import com.fivesec.app.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUi(val total: Int, val canceled: Int, val opened: Int, val streak: Int)

data class AppRangeStatsUi(
    val packageName: String,
    val appName: String,
    val brandColorArgb: Int,
    val interceptions: Int,
    val opened: Int,
    val canceled: Int,
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

    /** 当前时间档位（日/周/月/年），默认"日"。 */
    private val _selectedRange = MutableStateFlow(StatsRange.DAY)
    val selectedRange: StateFlow<StatsRange> = _selectedRange.asStateFlow()

    fun selectRange(range: StatsRange) {
        if (range == _selectedRange.value) return
        _selectedRange.value = range
    }

    /** 每个目标应用在所选档位周期内的统计（拦截/打开/取消）+ 品牌色，供统计页渲染卡片。
     *  档位切换时以当前时间重算周期起点并重新订阅查询。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val appRangeStats: StateFlow<List<AppRangeStatsUi>> =
        selectedRange
            .flatMapLatest { range ->
                val rangeStart = range.startMillis(timeProvider.now())
                combine(
                    targetAppRepository.observeAll(),
                    interceptionRepository.observeCountsByPackageSince(rangeStart),
                    brandColors,
                ) { targets, counts, colors ->
                    val byPkg = counts.associateBy { it.packageName }
                    targets.map { t ->
                        val c = byPkg[t.packageName]
                        AppRangeStatsUi(
                            packageName = t.packageName,
                            appName = t.appName,
                            brandColorArgb = colors[t.packageName] ?: FALLBACK_BRAND_ARGB,
                            interceptions = c?.total ?: 0,
                            opened = c?.opened ?: 0,
                            canceled = c?.canceled ?: 0,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

}
