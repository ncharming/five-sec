package com.fivesec.app.settings

import com.fivesec.app.data.db.HintDao
import com.fivesec.app.data.repository.HintRepository
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.HintKind
import com.fivesec.app.settings.viewmodels.HintListViewModel
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

@OptIn(ExperimentalCoroutinesApi::class)
class HintListViewModelTest {

    /** HintRepository 的写操作是 fire-and-forget，fake 记录后轮询断言（同 HintRepositoryTest 模式）。 */
    private class RecordingDao : HintDao {
        val poolState = MutableStateFlow<List<Hint>>(emptyList())
        val inserted = CopyOnWriteArrayList<Hint>()
        val deletedIds = CopyOnWriteArrayList<Long>()

        override suspend fun insert(hint: Hint): Long {
            inserted += hint
            return inserted.size.toLong()
        }

        override fun observeByKind(kind: String): Flow<List<Hint>> = poolState

        override suspend fun deleteById(id: Long) {
            deletedIds += id
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dao: RecordingDao
    private lateinit var repo: HintRepository
    private lateinit var viewModel: HintListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = RecordingDao()
        repo = HintRepository(dao)
        viewModel = HintListViewModel(repo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `add会trim并转发到池`() = runTest(dispatcher) {
        viewModel.add("  早点睡觉  ")
        // ViewModel 立即同步调用 Repository（其内部再异步落库），等后台协程排空
        waitUntil { dao.inserted.isNotEmpty() }
        assertEquals(listOf("早点睡觉" to HintKind.POOL), dao.inserted.map { it.text to it.kind })
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
        waitUntil { dao.inserted.isNotEmpty() }
        assertEquals(forty.take(30), dao.inserted.single().text)
    }

    @Test
    fun `remove按id转发`() = runTest(dispatcher) {
        viewModel.remove(7L)
        waitUntil { dao.deletedIds.contains(7L) }
    }

    /** Repository 的 scope 是真实 Default 线程，runTest 虚拟时间外运行：轮询 + 实时限。 */
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("wait timeout")
            Thread.sleep(10)
        }
    }

    private fun advanceUntilIdleAndFlush() {
        dispatcher.scheduler.advanceUntilIdle()
        Thread.sleep(150) // 排空可能存在的后台 launch
    }
}
