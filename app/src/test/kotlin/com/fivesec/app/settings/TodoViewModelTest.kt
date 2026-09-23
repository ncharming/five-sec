package com.fivesec.app.settings

import com.fivesec.app.data.db.TodoDao
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.settings.viewmodels.TodoViewModel
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * TodoViewModel 测试（specs/005-daily-todos；006 增重复规则）：
 * 今日勾选按 VM 持有的 today 口径转发（与 rows 派生口径同源）、refreshToday 跨日重算（完成态+灰显态）、
 * add 空白忽略与 200 字截断、dueToday 按规则派生、setRecurrence/add 规则转发。
 * Repository 写操作 fire-and-forget，fake 记录后轮询断言（同 HintListViewModelTest 模式）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModelTest {

    /** 手控 fake：observeAll 返回 StateFlow；定向 UPDATE 按列记录。 */
    private class RecordingDao : TodoDao {
        val state = MutableStateFlow<List<Todo>>(emptyList())
        val inserted = CopyOnWriteArrayList<Todo>()
        val completionChanges = CopyOnWriteArrayList<Pair<Long, String>>()
        val recurrenceCalls = CopyOnWriteArrayList<RecurrenceCall>()

        override fun observeAll(): Flow<List<Todo>> = state

        override suspend fun insert(todo: Todo): Long {
            inserted += todo
            return inserted.size.toLong()
        }

        override suspend fun updateText(id: Long, text: String) = Unit

        override suspend fun setEnabled(id: Long, enabled: Boolean) = Unit

        override suspend fun setCompletedDate(id: Long, date: String) {
            completionChanges += id to date
        }

        override suspend fun updateRecurrence(id: Long, repeatType: Int, repeatDays: Int, intervalDays: Int) {
            recurrenceCalls += RecurrenceCall(id, repeatType, repeatDays, intervalDays)
        }

        override suspend fun deleteById(id: Long) = Unit

        override suspend fun count(): Int = state.value.size
    }

    /** setRecurrence 定向 UPDATE 的转发记录。 */
    private data class RecurrenceCall(val id: Long, val type: Int, val days: Int, val interval: Int)

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dao: RecordingDao
    private lateinit var viewModel: TodoViewModel

    // 可拨动的假时钟：today 由 VM 经 DateUtil 派生，断言用同一 API 计算（对时区免疫，CI UTC 可跑）
    private var nowMillis = 0L
    private val timeProvider = TimeProvider { nowMillis }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = RecordingDao()
        viewModel = TodoViewModel(TodoRepository(dao), timeProvider)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setCompleted按当前today口径转发`() = runTest(dispatcher) {
        nowMillis = 1_789_000_000_000L // 任意固定时刻
        viewModel.refreshToday()

        viewModel.setCompleted(1, true)
        advanceUntilIdleAndFlush() // VM 写路径挂在 Main 测试调度器上：先排空队列再断言

        val expectedToday = DateUtil.todayString(nowMillis)
        assertEquals(listOf(1L to expectedToday), dao.completionChanges.toList())
    }

    @Test
    fun `refreshToday跨日后勾选写入新日期`() = runTest(dispatcher) {
        nowMillis = 1_789_000_000_000L
        viewModel.refreshToday()
        viewModel.setCompleted(1, true)
        advanceUntilIdleAndFlush()

        // 次日同一时刻：跨日重算后勾选另一条，日期应随之前移一天
        nowMillis += 24 * 60 * 60 * 1000L
        viewModel.refreshToday()
        viewModel.setCompleted(2, true)
        advanceUntilIdleAndFlush()

        val day1 = DateUtil.todayString(1_789_000_000_000L)
        val day2 = DateUtil.todayString(1_789_000_000_000L + 24 * 60 * 60 * 1000L)
        assertEquals(listOf(1L to day1, 2L to day2), dao.completionChanges.toList())
    }

    @Test
    fun `add空白被忽略`() = runTest(dispatcher) {
        viewModel.add("   ")
        viewModel.add("")
        advanceUntilIdleAndFlush()
        assertEquals(0, dao.inserted.size)
    }


    @Test
    fun `add超长截断200字`() = runTest(dispatcher) {
        val overlong = "背".repeat(210) // 超过 200 字上限的输入
        viewModel.add(overlong)
        advanceUntilIdleAndFlush()
        assertEquals(overlong.take(TodoRepository.MAX_TEXT_LENGTH), dao.inserted.single().text)
    }

    // ── 重复规则（specs/006） ──

    @Test
    fun `dueToday按重复规则派生`() = runTest(dispatcher) {
        nowMillis = 1_789_000_000_000L
        val todayStr = DateUtil.todayString(nowMillis)
        val hitBit = TodoRecurrence.bitOf(LocalDate.parse(todayStr).dayOfWeek)
        val missBit = TodoRecurrence.bitOf(LocalDate.parse(todayStr).dayOfWeek.plus(1))
        dao.state.value = listOf(
            Todo(id = 1, text = "周几命中", repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = hitBit),
            Todo(id = 2, text = "周几未命中", repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = missBit),
            Todo(id = 3, text = "间隔从未完成", repeatType = TodoRecurrence.REPEAT_INTERVAL, intervalDays = 3),
            Todo(
                id = 4,
                text = "间隔完成当天",
                repeatType = TodoRecurrence.REPEAT_INTERVAL,
                intervalDays = 3,
                lastCompletedDate = todayStr,
            ),
        )
        advanceUntilIdleAndFlush()

        val due = viewModel.rows.value.associate { it.todo.id to it.dueToday }
        assertEquals(true, due[1L])
        assertEquals(false, due[2L])
        assertEquals(true, due[3L])
        assertEquals(false, due[4L])
    }

    @Test
    fun `refreshToday跨日后dueToday重算`() = runTest(dispatcher) {
        nowMillis = 1_789_000_000_000L
        val day1 = DateUtil.todayString(nowMillis)
        dao.state.value = listOf(
            Todo(
                id = 1,
                text = "只在今天轮到",
                repeatType = TodoRecurrence.REPEAT_WEEKLY,
                repeatDays = TodoRecurrence.bitOf(LocalDate.parse(day1).dayOfWeek),
            ),
        )
        advanceUntilIdleAndFlush()
        assertEquals(true, viewModel.rows.value.single().dueToday)

        // 次日同一时刻：跨日重算后不再轮到（灰显）
        nowMillis += 24 * 60 * 60 * 1000L
        viewModel.refreshToday()
        advanceUntilIdleAndFlush()
        assertEquals(false, viewModel.rows.value.single().dueToday)
    }

    @Test
    fun `setRecurrence间隔越界收敛并按三列转发`() = runTest(dispatcher) {
        viewModel.setRecurrence(1, TodoRule(TodoRecurrence.REPEAT_INTERVAL, 0, 999))
        advanceUntilIdleAndFlush()
        assertEquals(
            listOf(RecurrenceCall(1L, TodoRecurrence.REPEAT_INTERVAL, 0, 365)),
            dao.recurrenceCalls.toList(),
        )
    }

    @Test
    fun `add透传规则落库`() = runTest(dispatcher) {
        viewModel.add("锻炼", TodoRule.weekly(setOf(DayOfWeek.WEDNESDAY)))
        advanceUntilIdleAndFlush()
        assertEquals(TodoRecurrence.REPEAT_WEEKLY, dao.inserted.single().repeatType)
    }

    private fun advanceUntilIdleAndFlush() {
        dispatcher.scheduler.advanceUntilIdle() // 排空 Main 调度器上的 VM 协程
        Thread.sleep(150) // 排空可能存在的 Repository 真实后台协程
    }
}
