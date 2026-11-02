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
import com.fivesec.app.util.ResistRate
import com.fivesec.app.util.ReviewTrend
import com.fivesec.app.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StatsUi(val total: Int, val canceled: Int, val opened: Int, val streak: Int)

/** 复盘卡档位（specs/014）：周（本周 vs 上周，默认）/ 月（本月 vs 上月）。 */
enum class ReviewRange { WEEK, MONTH }

/** 复盘卡单指标（specs/014）：本期值 + 上期值（null=无基线）+ 数值方向箭头 + 语义趋势
 *  （箭头跟数值方向、颜色跟语义：如拦截次数下降=数值↓+语义变好=绿）。 */
data class ReviewMetricUi(
    val current: String,
    val previous: String?,
    val arrow: Int,
    val trend: ReviewTrend.Trend,
)

/** 复盘卡四指标（specs/014）：拦截/抵制率/待办完成/平均停留。 */
data class ReviewCardUi(
    val range: ReviewRange,
    val interceptions: ReviewMetricUi,
    val resistRate: ReviewMetricUi,
    val completions: ReviewMetricUi,
    val avgStayMinutes: ReviewMetricUi,
) {
    companion object {
        /** 初值占位（stateIn 初始值）：四行均为「—」无基线态。 */
        val EMPTY = ReviewCardUi(
            range = ReviewRange.WEEK,
            interceptions = ReviewMetricUi("—", null, 0, ReviewTrend.Trend.NO_BASELINE),
            resistRate = ReviewMetricUi("—", null, 0, ReviewTrend.Trend.NO_BASELINE),
            completions = ReviewMetricUi("—", null, 0, ReviewTrend.Trend.NO_BASELINE),
            avgStayMinutes = ReviewMetricUi("—", null, 0, ReviewTrend.Trend.NO_BASELINE),
        )
    }
}

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

/**
 * 统计页状态源（specs/003/008/011/012）。
 *
 * 「今日」锚点随零点滚动（2026-11 修复：锚点曾在构造时定格，深夜常驻统计页跨零点后持续
 * 显示昨日数据）：[todayKey] 每 30s 采样一次本地日期，日期变化才向下游发射——今日四数与
 * 今日任务按它 `flatMapLatest` 重订阅新一天的查询窗口；选中的「当前周期」也跟随翻日滚动。
 *
 * 重聚合链全部 `WhileSubscribed(5s)`（2026-11 与 Eagerly 约定一并修订）：VM 随 Home 返回栈
 * 常驻存活，Eagerly 会让用户在其他 Tab/后台时每次事件 INSERT 都触发 6 条链的 Room 重聚合；
 * 改为无 UI 订阅即停，仅 [todayKey]（本地日期计算，零 IO）与品牌色收集器（≤3 行的
 * target_apps 轻表）常驻。
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val interceptionRepository: InterceptionRepository,
    private val targetAppRepository: TargetAppRepository,
    private val todoRepository: TodoRepository,
    private val usageSessionRepository: UsageSessionRepository,
    private val brandColorExtractor: AppBrandColorExtractor,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val zone = ZoneId.systemDefault()
    private val initialNow = timeProvider.now() // 仅初始值用（默认周期/初值占位），后续由 todayKey 滚动
    private val initialToday = DateUtil.todayString(initialNow, zone)

    /** 今日键（yyyy-MM-dd）：采样一次本地日期计算、零 IO；协程延时在设备休眠时挂起无耗电代价。 */
    private val todayKey: SharedFlow<String> = flow {
        while (true) {
            emit(DateUtil.todayString(timeProvider.now()))
            delay(DAY_POLL_MS)
        }
    }
        .distinctUntilChanged()
        .shareIn(viewModelScope, SharingStarted.Eagerly, replay = 1)

    /** 今日四数（拦截/取消/打开/连击）：按今日键重订阅——跨零点自动切到新一天的查询窗口；
     *  连击数据源带窗口下界（见 DAO 注释，窗口远大于任何现实连击）。 */
    val ui: StateFlow<StatsUi> = todayKey
        .flatMapLatest { today ->
            val startOfDay = requireNotNull(DateUtil.dateStringToMillis(today, zone))
            val activeSince = LocalDate.parse(today).minusDays(ACTIVE_DAYS_WINDOW_DAYS).toString()
            combine(
                interceptionRepository.observeStats(startOfDay),
                interceptionRepository.observeActiveDays(activeSince),
            ) { day, days ->
                StatsUi(
                    total = day.total,
                    canceled = day.canceled,
                    opened = day.opened,
                    streak = DateUtil.computeStreak(days, today),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatsUi(0, 0, 0, 0))

    /** 今日任务三数 + 今日补完（specs/008；2026-10-04 补完标记）：todos 行集实时派生 + 事件表
     *  过期补完聚合，同按今日键重订阅。 */
    val todoToday: StateFlow<TodoTodayStatsUi> = todayKey
        .flatMapLatest { today ->
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
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodoTodayStatsUi(0, 0, 0, 0))

    /** 应用品牌色（ARGB），按包名；提取在后台进行，就绪后逐个回填。 */
    private val brandColors = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** 当前时间档位（日/周/月/年），默认"日"；两个二级页共享（specs/008：同一时间视角对比两块数据）。 */
    private val _selectedRange = MutableStateFlow(StatsRange.DAY)
    val selectedRange: StateFlow<StatsRange> = _selectedRange.asStateFlow()

    /** 当前时间档位下选中的自然周期，默认最新周期。 */
    private val _selectedPeriod = MutableStateFlow(StatsRange.DAY.currentPeriod(initialNow, zone))
    val selectedPeriod: StateFlow<StatsPeriod> = _selectedPeriod.asStateFlow()

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
        // 翻日跟随：选中的「当前周期」跨零点后滚动到新周期（「今天」从昨天切到今天、本周→新本周…）
        viewModelScope.launch {
            var previous: String? = null
            todayKey.collect { today ->
                if (previous != null && today != previous && _selectedPeriod.value.isCurrent) {
                    _selectedPeriod.value = _selectedPeriod.value.range.currentPeriod(
                        requireNotNull(DateUtil.dateStringToMillis(today, zone)),
                        zone,
                    )
                }
                previous = today
            }
        }
    }

    /** 筛选行可选项：周仅本周/上周，月至当年 1 月，年至最早数据年份——
     *  拦截最早事件与完成最早日期二者取更早（年档位两个二级页都覆盖各自历史，specs/008）。 */
    val availablePeriods: StateFlow<List<StatsPeriod>> =
        combine(
            _selectedRange,
            combine(
                interceptionRepository.observeEarliestTimestamp(),
                todoRepository.observeEarliestCompletionDate(),
            ) { earliestEvent, earliestCompletion ->
                listOfNotNull(earliestEvent, DateUtil.dateStringToMillis(earliestCompletion, zone)).minOrNull()
            },
            todayKey,
        ) { range, earliest, today ->
            range.availablePeriods(
                requireNotNull(DateUtil.dateStringToMillis(today, zone)),
                zone,
                earliest,
            )
        }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                StatsRange.DAY.availablePeriods(initialNow, zone),
            )

    fun selectRange(range: StatsRange) {
        _selectedRange.value = range
        // 当前日期取今日键的已发值（首发射前 replayCache 仍空，退回构造锚点）
        val today = todayKey.replayCache.firstOrNull() ?: initialToday
        _selectedPeriod.value = range.currentPeriod(
            requireNotNull(DateUtil.dateStringToMillis(today, zone)),
            zone,
        )
    }

    /**
     * 选择周期：仅做档位结构校验——成员校验读流瞬时值反而制造竞态（切档瞬间旧列表静默吞掉
     * 合法选择）；合法选项的唯一来源本就是 [availablePeriods] 渲染出的 UI 列表。
     */
    fun selectPeriod(period: StatsPeriod) {
        if (period.range != _selectedRange.value) return
        _selectedPeriod.value = period
    }

    /** 每个目标应用在所选自然周期内的统计（拦截/打开/取消）+ 停留时长 + 品牌色，供统计页渲染卡片。
     *  周期切换时重新订阅查询；页面停留跨周期不自动刷新（跨零点的当前周期跟随见 init 翻日收集器）。
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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

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
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), List(HourDistribution.BUCKETS) { 0 })

    // ── 复盘卡（specs/014）：本期 vs 上期对比，主页双 Hero 卡之下 ──

    private val _reviewRange = MutableStateFlow(ReviewRange.WEEK)
    val reviewRange: StateFlow<ReviewRange> = _reviewRange.asStateFlow()

    fun setReviewRange(range: ReviewRange) {
        _reviewRange.value = range
    }

    /** 复盘双窗口（本期/上期，自然周期半开区间）：周=本周一起 vs 上周，月=本月 1 号 vs 上月。 */
    private data class ReviewWindow(val start: LocalDate, val end: LocalDate) {
        fun startMillis(zone: ZoneId): Long = start.atStartOfDay(zone).toInstant().toEpochMilli()
        fun endMillis(zone: ZoneId): Long = end.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    private fun reviewWindows(range: ReviewRange, today: String): Pair<ReviewWindow, ReviewWindow> {
        val todayDate = LocalDate.parse(today)
        return when (range) {
            ReviewRange.WEEK -> {
                val cur = ReviewWindow(todayDate.with(DayOfWeek.MONDAY), todayDate.with(DayOfWeek.MONDAY).plusWeeks(1))
                cur to ReviewWindow(cur.start.minusWeeks(1), cur.start)
            }

            ReviewRange.MONTH -> {
                val cur = ReviewWindow(todayDate.withDayOfMonth(1), todayDate.withDayOfMonth(1).plusMonths(1))
                cur to ReviewWindow(cur.start.minusMonths(1), cur.start)
            }
        }
    }

    /** 单期原始聚合（既有区间查询求和，零新 DAO）：拦截三计数、完成计数、会话时长与场次。 */
    private data class ReviewPeriodRaw(
        val interceptions: Int,
        val canceled: Int,
        val opened: Int,
        val completions: Int,
        val stayMillis: Long,
        val sessionCount: Int,
    )

    private fun reviewPeriodSummary(start: Long, end: Long) = combine(
        interceptionRepository.observeCountsByPackageBetween(start, end),
        todoRepository.observeCompletionCountByTodoBetween(
            DateUtil.millisToDateString(start, zone),
            DateUtil.millisToDateString(end, zone),
        ),
        usageSessionRepository.observeDurationByPackageBetween(start, end),
    ) { pkgCounts, todoCounts, sessions ->
        ReviewPeriodRaw(
            interceptions = pkgCounts.sumOf { it.total },
            canceled = pkgCounts.sumOf { it.canceled },
            opened = pkgCounts.sumOf { it.opened },
            completions = todoCounts.sumOf { it.completions },
            stayMillis = sessions.sumOf { it.totalMillis },
            sessionCount = sessions.sumOf { it.sessionCount },
        )
    }

    /** 数值型指标行：上期无数据/为 0 → 无基线（箭头归零、上期文本空）。 */
    private fun countMetric(current: Int, previous: Int, higherIsBetter: Boolean, unit: String = ""): ReviewMetricUi {
        val hasBaseline = previous > 0
        return ReviewMetricUi(
            current = "$current$unit",
            previous = if (hasBaseline) "$previous$unit" else null,
            arrow = if (hasBaseline) current.compareTo(previous).coerceIn(-1, 1) else 0,
            trend = ReviewTrend.countTrend(current, if (hasBaseline) previous else null, higherIsBetter),
        )
    }

    /** 抵制率行：任一分母 0 → 无基线；rate 为 ResistRate 百分比整数。 */
    private fun rateMetric(currentRate: Int?, previousRate: Int?): ReviewMetricUi = ReviewMetricUi(
        current = currentRate?.let { "$it%" } ?: "—",
        previous = previousRate?.let { "$it%" },
        arrow = if (currentRate != null && previousRate != null) {
            currentRate.compareTo(previousRate).coerceIn(-1, 1)
        } else {
            0
        },
        trend = ReviewTrend.rateTrend(currentRate, previousRate),
    )

    /** 平均停留行（分钟向上取整）：任一期无会话 → 该期「—」，本期无会话不参与方向判定。 */
    private fun avgStayMetric(curRaw: ReviewPeriodRaw, prevRaw: ReviewPeriodRaw): ReviewMetricUi {
        val cur = if (curRaw.sessionCount > 0) {
            ((curRaw.stayMillis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
        } else {
            null
        }
        val prev = if (prevRaw.sessionCount > 0) {
            ((prevRaw.stayMillis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE).toInt()
        } else {
            null
        }
        return if (cur == null || prev == null) {
            ReviewMetricUi(
                current = cur?.let { "$it 分钟" } ?: "—",
                previous = prev?.let { "$it 分钟" },
                arrow = 0,
                trend = ReviewTrend.Trend.NO_BASELINE,
            )
        } else {
            countMetric(cur, prev, higherIsBetter = false, unit = " 分钟")
        }
    }

    /** 复盘卡（specs/014）：本期 vs 上期四指标；档位与今日键双驱动——跨零点自动换窗。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val reviewStats: StateFlow<ReviewCardUi> =
        combine(_reviewRange, todayKey) { range, today -> range to reviewWindows(range, today) }
            .distinctUntilChanged()
            .flatMapLatest { (range, windows) ->
                val (cur, prev) = windows
                combine(
                    reviewPeriodSummary(cur.startMillis(zone), cur.endMillis(zone)),
                    reviewPeriodSummary(prev.startMillis(zone), prev.endMillis(zone)),
                ) { c, p ->
                    ReviewCardUi(
                        range = range,
                        interceptions = countMetric(c.interceptions, p.interceptions, higherIsBetter = false),
                        resistRate = rateMetric(
                            ResistRate.percent(c.canceled, c.opened),
                            ResistRate.percent(p.canceled, p.opened),
                        ),
                        completions = countMetric(c.completions, p.completions, higherIsBetter = true),
                        avgStayMinutes = avgStayMetric(c, p),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ReviewCardUi.EMPTY)

    companion object {
        /** 今日键采样间隔：跨零点后最长 30s 内切到新一天（本地日期计算，成本可忽略）。 */
        private const val DAY_POLL_MS = 30_000L

        /** 无 UI 订阅后查询链的存活余量（切 Tab/瞬断往返不清空画面、长离场即停）。 */
        private const val STOP_TIMEOUT_MS = 5_000L

        /** 连击窗口天数（约 3 年）：远大于任何现实连击，截断让 DISTINCT 聚合成本不随年限增长。 */
        private const val ACTIVE_DAYS_WINDOW_DAYS = 1_100L

        /** 复盘卡平均停留的分钟取整单位（与 UI 展示同值口径）。 */
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}
