package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.data.repository.UsageSessionRepository
import com.fivesec.app.util.AppBrandColorExtractor
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.FALLBACK_BRAND_ARGB
import com.fivesec.app.util.HourDistribution
import com.fivesec.app.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUi(val total: Int, val canceled: Int, val opened: Int, val streak: Int)

/** 今日任务三数卡（specs/008；口径见 [TodoTodayStatsCalculator]，与覆盖层 D/T 同源）。
 *  [lateCompleted]（2026-10-04）：今日「过期补完」次数——从过期区完成入口勾掉的条目数，
 *  事件表 wasExpired 聚合，与过期 n 并列展示（补了的看得见，没补的看得见）。 */
data class TodoTodayStatsUi(val total: Int, val completed: Int, val expired: Int, val lateCompleted: Int)

/** 任务历史二级页条目卡：事件行文本快照 + 周期内完成次数（删除的条目按快照展示不失联）。
 *  [lateCompletions]：其中过期补完次数——统计显式区分迟到完成（正常完成 = completions - lateCompletions）。 */
data class TodoRangeStatsUi(val todoText: String, val completions: Int, val lateCompletions: Int)

data class AppRangeStatsUi(
    val packageName: String,
    val appName: String,
    val brandColorArgb: Int,
    val interceptions: Int,
    val opened: Int,
    val canceled: Int,
    /** 周期内会话停留总毫秒（specs/012，墙钟口径）：0=无会话（UI 不显示停留行）。 */
    val stayMillis: Long = 0L,
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val interceptionRepository: InterceptionRepository,
    private val targetAppRepository: TargetAppRepository,
    private val todoRepository: TodoRepository,
    private val usageSessionRepository: UsageSessionRepository,
    private val brandColorExtractor: AppBrandColorExtractor,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val now = timeProvider.now()
    private val zone = ZoneId.systemDefault()
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

    /** 今日任务三数 + 今日补完（specs/008；2026-10-04 补完标记）：todos 行集实时派生 + 事件表
     *  过期补完聚合，today 沿用构造锚点（与今日拦截卡同一跨日口径）。 */
    val todoToday: StateFlow<TodoTodayStatsUi> =
        combine(
            todoRepository.observeAll(),
            todoRepository.observeLateCompletionCountBetween(today, LocalDate.parse(today).plusDays(1).toString()),
        ) { rows, lateCount ->
            TodoTodayStatsCalculator.compute(rows, today).let {
                TodoTodayStatsUi(
                    total = it.total,
                    completed = it.completed,
                    expired = it.expired,
                    lateCompleted = lateCount,
                )
            }
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, TodoTodayStatsUi(0, 0, 0, 0))

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

    /** 当前时间档位（日/周/月/年），默认"日"；两个二级页共享（specs/008：同一时间视角对比两块数据）。 */
    private val _selectedRange = MutableStateFlow(StatsRange.DAY)
    val selectedRange: StateFlow<StatsRange> = _selectedRange.asStateFlow()

    /** 当前时间档位下选中的自然周期，默认最新周期。 */
    private val _selectedPeriod = MutableStateFlow(StatsRange.DAY.currentPeriod(now, zone))
    val selectedPeriod: StateFlow<StatsPeriod> = _selectedPeriod.asStateFlow()

    /** 筛选行可选项：周仅本周/上周，月至当年 1 月，年至最早数据年份——
     *  拦截最早事件与完成最早日期二者取更早（年档位两个二级页都覆盖各自历史，specs/008）。 */
    val availablePeriods: StateFlow<List<StatsPeriod>> =
        _selectedRange
            .combine(
                combine(
                    interceptionRepository.observeEarliestTimestamp(),
                    todoRepository.observeEarliestCompletionDate(),
                ) { earliestEvent, earliestCompletion ->
                    listOfNotNull(earliestEvent, DateUtil.dateStringToMillis(earliestCompletion, zone)).minOrNull()
                },
            ) { range, earliest ->
                range.availablePeriods(now, zone, earliest)
            }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                StatsRange.DAY.availablePeriods(now, zone),
            )

    fun selectRange(range: StatsRange) {
        _selectedRange.value = range
        _selectedPeriod.value = range.currentPeriod(now, zone)
    }

    fun selectPeriod(period: StatsPeriod) {
        if (period.range != _selectedRange.value) return
        if (period !in availablePeriods.value) return
        _selectedPeriod.value = period
    }

    /** 每个目标应用在所选自然周期内的统计（拦截/打开/取消）+ 停留时长 + 品牌色，供统计页渲染卡片。
     *  周期切换时重新订阅查询；页面停留跨周期不自动刷新（沿用现状）。
     *  stayMillis（specs/012）：会话按 endedAt 归期聚合（半开区间），墙钟口径。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val appRangeStats: StateFlow<List<AppRangeStatsUi>> =
        selectedPeriod
            .flatMapLatest { period ->
                combine(
                    targetAppRepository.observeAll(),
                    interceptionRepository.observeCountsByPackageBetween(period.startMillis, period.endMillis),
                    usageSessionRepository.observeDurationByPackageBetween(period.startMillis, period.endMillis),
                    brandColors,
                ) { targets, counts, durations, colors ->
                    val byPkg = counts.associateBy { it.packageName }
                    val stayByPkg = durations.associateBy { it.packageName }
                    targets.map { t ->
                        val c = byPkg[t.packageName]
                        AppRangeStatsUi(
                            packageName = t.packageName,
                            appName = t.appName,
                            brandColorArgb = colors[t.packageName] ?: FALLBACK_BRAND_ARGB,
                            interceptions = c?.total ?: 0,
                            opened = c?.opened ?: 0,
                            canceled = c?.canceled ?: 0,
                            stayMillis = stayByPkg[t.packageName]?.totalMillis ?: 0L,
                        )
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 所选自然周期内按条目的完成次数与过期补完拆分（specs/008 任务历史二级页）：总次数由 UI 侧
     *  sumOf 派生。周期端点毫秒 → yyyy-MM-dd 半开区间字符串比较（字典序=时间序）；与 appRangeStats 共享周期选择。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val todoRangeStats: StateFlow<List<TodoRangeStatsUi>> =
        selectedPeriod
            .flatMapLatest { period ->
                todoRepository.observeCompletionCountByTodoBetween(
                    startDate = DateUtil.millisToDateString(period.startMillis, zone),
                    endDate = DateUtil.millisToDateString(period.endMillis, zone),
                ).map { rows ->
                    rows.map {
                        TodoRangeStatsUi(todoText = it.todoText, completions = it.completions, lateCompletions = it.lateCompletions)
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 所选周期内拦截事件的 24 小时分布（specs/011 时段分布卡）：0–23 时桶、本地时区，
     *  含全部结局（与「拦截次数」总数同口径）。分桶是显式 ZoneId 的纯函数
     *  （[HourDistribution]）；周期切换经 flatMapLatest 重订阅，与 appRangeStats 同模式。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val hourDistribution: StateFlow<List<Int>> =
        selectedPeriod
            .flatMapLatest { period ->
                interceptionRepository.observeTimestampsBetween(period.startMillis, period.endMillis)
                    .map { HourDistribution.of(it, zone).toList() }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, List(HourDistribution.BUCKETS) { 0 })

}
