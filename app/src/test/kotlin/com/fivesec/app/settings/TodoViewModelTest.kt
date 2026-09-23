package com.fivesec.app.settings

import com.fivesec.app.data.db.TodoDao
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.settings.viewmodels.TodoViewModel
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
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
 * TodoViewModel 测试（specs/005-daily-todos）：
 * 今日勾选按 VM 持有的 today 口径转发（与 rows 派生口径同源）、refreshToday 跨日重算、
 * add 空白忽略与 30 字截断。Repository 写操作 fire-and-forget，fake 记录后轮询断言（同 HintListViewModelTest 模式）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TodoViewModelTest {

    /** 手控 fake：observeAll 返回 StateFlow；定向 UPDATE 按列记录。 */
    private class RecordingDao : TodoDao {
        val state = MutableStateFlow<List<Todo>>(emptyList())
        val inserted = CopyOnWriteArrayList<Todo>()
        val completionChanges = CopyOnWriteArrayList<Pair<Long, String>>()

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

        override suspend fun deleteById(id: Long) = Unit

        override suspend fun count(): Int = state.value.size
    }

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
    fun `add超长截断30字`() = runTest(dispatcher) {
        val forty = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四十"
        viewModel.add(forty)
        advanceUntilIdleAndFlush()
        assertEquals(forty.take(30), dao.inserted.single().text)
    }

    private fun advanceUntilIdleAndFlush() {
        dispatcher.scheduler.advanceUntilIdle() // 排空 Main 调度器上的 VM 协程
        Thread.sleep(150) // 排空可能存在的 Repository 真实后台协程
    }
}
