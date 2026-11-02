package com.fivesec.app.settings

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.data.db.InterceptionEventDao
import com.fivesec.app.data.db.PackageDurationRow
import com.fivesec.app.data.db.PackageRangeCount
import com.fivesec.app.data.db.TargetAppDao
import com.fivesec.app.data.db.TodoCompletionDao
import com.fivesec.app.data.db.TodoDao
import com.fivesec.app.data.db.TodoRangeCount
import com.fivesec.app.data.db.UsageSessionDao
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.data.repository.UsageSessionRepository
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.TargetApp
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoCompletion
import com.fivesec.app.domain.model.UsageSession
import com.fivesec.app.settings.viewmodels.ReviewRange
import com.fivesec.app.settings.viewmodels.StatsRange
import com.fivesec.app.settings.viewmodels.StatsViewModel
import com.fivesec.app.settings.viewmodels.currentPeriod
import com.fivesec.app.util.AppBrandColorExtractor
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
import com.fivesec.app.util.ReviewTrend.Trend
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [StatsViewModel] 本体行为（2026-11 补测试债）：
 *  - 「今日」锚点随零点滚动——此前锚点在构造时定格，深夜常驻统计页跨零点后持续显示昨日数据；
 *  - 周期选择结构校验（异档位忽略）与切档重置当前周期。
 *
 * 全部数据源为 StateFlow 假 DAO（零 Robolectric SQLite——真实 Room 的跨线程连接在
 * flatMapLatest 反复重订阅下会踩 legacy shadow 的「Illegal connection pointer」）；今天的
 * 翻日由 Main 测试调度器的虚拟时间推进（todayKey 采样 30s）。
 *
 * ⚠️ 常驻轮询契约：todayKey 是 while(true)+delay 的常驻任务源——测试体内**禁止
 * advanceUntilIdle**（无界推进=死循环），只能用 runCurrent/advanceTimeBy 有界推进，且用例
 * 收尾必须 viewModel.viewModelScope.cancel()（runTest 收尾要求调度器归寂）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatsViewModelFlowTest {

    @get:Rule val instantExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    // 固定起点：2026-11-01 12:00（默认时区；推导一律与生产代码同源走 DateUtil）
    private var nowMs: Long = LocalDate.parse("2026-11-01").atTime(12, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val time = TimeProvider { nowMs }

    private val eventDao = FakeInterceptionEventDao()
    private lateinit var viewModel: StatsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val todoDao = FakeTodoDao()
        val completionDao = FakeTodoCompletionDao()
        viewModel = StatsViewModel(
            interceptionRepository = InterceptionRepository(eventDao, time),
            targetAppRepository = TargetAppRepository(FakeTargetAppDao()),
            todoRepository = TodoRepository(todoDao, completionDao, time),
            usageSessionRepository = UsageSessionRepository(FakeUsageSessionDao()),
            brandColorExtractor = AppBrandColorExtractor(ApplicationProvider.getApplicationContext()),
            timeProvider = time,
        )
    }

    @After
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `今日四数随零点滚动到新一天`() = runTest(dispatcher) {
        eventDao.events.value = listOf(
            InterceptionEvent(
                packageName = "com.ss.android.ugc.aweme",
                timestamp = nowMs,
                exerciseCompleted = true,
                outcome = InterceptionOutcome.CANCELED,
            ),
        )
        val job = launch { viewModel.ui.collect { } } // 订阅驱动 WhileSubscribed 链
        val firstDay = viewModel.ui.first { it.total == 1 }
        assertEquals(1, firstDay.canceled)

        // 跨零点：时钟拨到次日 00:01，推进虚拟时间过采样间隔 → 今日窗口切到新一天（归零）
        nowMs = DateUtil.startOfDayMillis(nowMs) + 86_400_000L + 60_000L
        advanceTimeBy(31_000)
        val secondDay = viewModel.ui.first { it.total == 0 }
        assertEquals(0, secondDay.canceled)
        job.cancel()
        viewModel.viewModelScope.cancel()
    }

    @Test
    fun `切档重置当前周期且异档位周期选择被忽略`() = runTest(dispatcher) {
        runCurrent() // 今日键首发（selectRange 读 replayCache；禁 advanceUntilIdle，见类 KDoc）
        viewModel.selectRange(StatsRange.WEEK)
        assertEquals(StatsRange.WEEK, viewModel.selectedPeriod.value.range)
        assertTrue(viewModel.selectedPeriod.value.isCurrent)

        // 异档位（DAY 的周期）在 WEEK 档位下被结构性忽略——不吞不炸
        viewModel.selectPeriod(StatsRange.DAY.currentPeriod(nowMs))
        assertEquals(StatsRange.WEEK, viewModel.selectedPeriod.value.range)
        viewModel.viewModelScope.cancel()
    }

    /** specs/014 复盘卡：双窗口聚合随档位切换重订阅。固定日期 2026-11-01（周日）：
     *  本周=10-26..11-02、上周=10-19..10-26、本月=11 月、上月=10 月。 */
    @Test
    fun `复盘卡双窗口随档位切换`() = runTest(dispatcher) {
        val zone = ZoneId.systemDefault()
        fun atNoon(date: String): Long =
            LocalDate.parse(date).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val pkg = "com.ss.android.ugc.aweme"
        eventDao.events.value = listOf(
            InterceptionEvent(packageName = pkg, timestamp = atNoon("2026-10-27"), exerciseCompleted = true, outcome = InterceptionOutcome.OPENED), // 本周、上月
            InterceptionEvent(packageName = pkg, timestamp = atNoon("2026-10-28"), exerciseCompleted = true, outcome = InterceptionOutcome.OPENED), // 本周、上月
            InterceptionEvent(packageName = pkg, timestamp = atNoon("2026-10-20"), exerciseCompleted = true, outcome = InterceptionOutcome.OPENED), // 上周、上月
        )

        // 周档位（默认）：本期 2 vs 上期 1 → 拦截上升=变差（红 ↑）
        val week = viewModel.reviewStats.first { it.range == ReviewRange.WEEK && it.interceptions.current == "2" }
        assertEquals("1", week.interceptions.previous)
        assertEquals(1, week.interceptions.arrow)
        assertEquals(Trend.WORSE, week.interceptions.trend)

        // 切月档位：本期（11 月）0 vs 上期（10 月）3 → 拦截下降=变好（绿 ↓）
        viewModel.setReviewRange(ReviewRange.MONTH)
        val month = viewModel.reviewStats.first { it.range == ReviewRange.MONTH }
        assertEquals("0", month.interceptions.current)
        assertEquals("3", month.interceptions.previous)
        assertEquals(-1, month.interceptions.arrow)
        assertEquals(Trend.BETTER, month.interceptions.trend)
        viewModel.viewModelScope.cancel()
    }

    // ── 假 DAO：StateFlow 直推（派生查询尊重区间参数——今日窗口滚动必须真实重算） ──

    private class FakeInterceptionEventDao : InterceptionEventDao {
        val events = MutableStateFlow<List<InterceptionEvent>>(emptyList())

        override suspend fun insert(event: InterceptionEvent): Long {
            events.value = events.value + event
            return events.value.size.toLong()
        }

        override fun observeTodayCount(startOfDay: Long): Flow<Int> =
            events.map { list -> list.count { it.timestamp >= startOfDay } }

        override fun observeTodayCountByOutcome(startOfDay: Long, outcome: InterceptionOutcome): Flow<Int> =
            events.map { list -> list.count { it.timestamp >= startOfDay && it.outcome == outcome } }

        override fun observeCountsByPackageBetween(rangeStart: Long, rangeEnd: Long): Flow<List<PackageRangeCount>> =
            events.map { list ->
                list.filter { it.timestamp >= rangeStart && it.timestamp < rangeEnd }
                    .groupBy { it.packageName }
                    .map { (pkg, es) ->
                        PackageRangeCount(
                            packageName = pkg,
                            total = es.size,
                            opened = es.count { it.outcome == InterceptionOutcome.OPENED },
                            canceled = es.count { it.outcome == InterceptionOutcome.CANCELED },
                        )
                    }
            }

        override fun observeActiveDays(since: String): Flow<List<String>> =
            events.map { list ->
                list.filter { it.exerciseCompleted }
                    .map { DateUtil.todayString(it.timestamp) }
                    .filter { it >= since }
                    .distinct()
                    .sortedDescending()
            }

        override fun observeEarliestTimestamp(): Flow<Long?> =
            events.map { list -> list.minOfOrNull { it.timestamp } }

        override suspend fun countByOutcomeSince(startOfDay: Long, outcome: InterceptionOutcome): Int =
            events.value.count { it.timestamp >= startOfDay && it.outcome == outcome }

        override fun observeTimestampsBetween(rangeStart: Long, rangeEnd: Long): Flow<List<Long>> =
            events.map { list ->
                list.map { it.timestamp }
                    .filter { it >= rangeStart && it < rangeEnd }
                    .sorted()
            }
    }

    private class FakeTargetAppDao : TargetAppDao {
        private val apps = MutableStateFlow<List<TargetApp>>(emptyList())
        override fun observeAll(): Flow<List<TargetApp>> = apps
        override suspend fun count(): Int = apps.value.size
        override suspend fun upsert(app: TargetApp) = Unit
        override suspend fun insertAll(apps: List<TargetApp>) = Unit
        override suspend fun delete(packageName: String) = Unit
        override suspend fun setEnabled(packageName: String, enabled: Boolean) = Unit
        override suspend fun setSessionGuardEnabled(packageName: String, enabled: Boolean) = Unit
    }

    private class FakeTodoDao : TodoDao {
        private val rows = MutableStateFlow<List<Todo>>(emptyList())
        override fun observeAll(): Flow<List<Todo>> = rows
        override suspend fun findById(id: Long): Todo? = rows.value.firstOrNull { it.id == id }
        override suspend fun insert(todo: Todo): Long = 1L
        override suspend fun updateText(id: Long, text: String) = Unit
        override suspend fun setEnabled(id: Long, enabled: Boolean) = Unit
        override suspend fun setCompletedDate(id: Long, date: String) = Unit
        override suspend fun updateRecurrence(
            id: Long,
            repeatType: Int,
            repeatDays: Int,
            intervalDays: Int,
            dueDate: String,
        ) = Unit

        override suspend fun setDueDate(id: Long, dueDate: String) = Unit
        override suspend fun updateReminderTimes(id: Long, times: String) = Unit
        override suspend fun findByIds(ids: List<Long>): List<Todo> =
            rows.value.filter { it.id in ids }.sortedBy { it.id }

        override suspend fun purgeCompletedOneOffs(today: String) = Unit
        override suspend fun deleteById(id: Long) = Unit
        override suspend fun count(): Int = rows.value.size
    }

    private class FakeTodoCompletionDao : TodoCompletionDao {
        override suspend fun upsert(completion: TodoCompletion) = Unit
        override suspend fun deleteByTodoAndDate(todoId: Long, date: String) = Unit
        override fun observeCountByTodoBetween(startDate: String, endDate: String): Flow<List<TodoRangeCount>> =
            MutableStateFlow(emptyList())
        override fun observeLateCountBetween(startDate: String, endDate: String): Flow<Int> =
            MutableStateFlow(0)
        override fun observeEarliestDate(): Flow<String?> = MutableStateFlow(null)
    }

    private class FakeUsageSessionDao : UsageSessionDao {
        override suspend fun insert(session: UsageSession) = Unit
        override fun observeDurationByPackageBetween(start: Long, end: Long): Flow<List<PackageDurationRow>> =
            MutableStateFlow(emptyList())
    }
}
