package com.fivesec.app.settings

import com.fivesec.app.data.datastore.BuiltinHintsSetting
import com.fivesec.app.data.db.HintDao
import com.fivesec.app.data.repository.HintRepository
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.HintKind
import com.fivesec.app.settings.viewmodels.HintListViewModel
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /**
     * 手控 fake 开关存储：StateFlow 持有"已持久化"值（模拟 DataStore 读回），
     * setBuiltinHintsEnabled 记录写入序并可注入 IOException 模拟存储异常。
     */
    private class FakeBuiltinHintsSetting(
        initial: Boolean = true,
        private val failWrites: Boolean = false,
    ) : BuiltinHintsSetting {
        val state = MutableStateFlow(initial)
        val saved = CopyOnWriteArrayList<Boolean>()
        override val builtinHintsEnabled: Flow<Boolean> = state
        override suspend fun setBuiltinHintsEnabled(enabled: Boolean) {
            if (failWrites) throw IOException("disk full")
            saved += enabled
            state.value = enabled
        }
    }

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dao: RecordingDao
    private lateinit var repo: HintRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        dao = RecordingDao()
        repo = HintRepository(dao, FakeBuiltinHintsSetting())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `add会trim并转发到池`() = runTest(dispatcher) {
        val viewModel = HintListViewModel(repo, FakeBuiltinHintsSetting())
        viewModel.add("  早点睡觉  ")
        // ViewModel 立即同步调用 Repository（其内部再异步落库），等后台协程排空
        waitUntil { dao.inserted.isNotEmpty() }
        assertEquals(listOf("早点睡觉" to HintKind.POOL), dao.inserted.map { it.text to it.kind })
    }

    @Test
    fun `add空白被忽略`() = runTest(dispatcher) {
        val viewModel = HintListViewModel(repo, FakeBuiltinHintsSetting())
        viewModel.add("   ")
        viewModel.add("")
        advanceUntilIdleAndFlush()
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `add超长截断30字`() = runTest(dispatcher) {
        val viewModel = HintListViewModel(repo, FakeBuiltinHintsSetting())
        val forty = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四十"
        viewModel.add(forty)
        waitUntil { dao.inserted.isNotEmpty() }
        assertEquals(forty.take(30), dao.inserted.single().text)
    }

    @Test
    fun `remove按id转发`() = runTest(dispatcher) {
        val viewModel = HintListViewModel(repo, FakeBuiltinHintsSetting())
        viewModel.remove(7L)
        waitUntil { dao.deletedIds.contains(7L) }
    }

    @Test
    fun `开关首次使用默认开启-有历史配置则跟随存储`() = runTest(dispatcher) {
        // 无历史配置：StateFlow 初值即默认开启（与现行为一致）
        val fresh = HintListViewModel(repo, FakeBuiltinHintsSetting(initial = true))
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(fresh.builtinEnabled.value)

        // 有历史配置（关）：新 VM（模拟重启/刷新后重建）读到存储值
        val persisted = FakeBuiltinHintsSetting(initial = false)
        val restored = HintListViewModel(repo, persisted)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, restored.builtinEnabled.value)
    }

    @Test
    fun `切换开关乐观更新并持久化-重建后保持`() = runTest(dispatcher) {
        val setting = FakeBuiltinHintsSetting(initial = true)
        val viewModel = HintListViewModel(repo, setting)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.setBuiltinEnabled(false)
        // 乐观更新：保存协程尚未排空时状态已跟手
        assertEquals(false, viewModel.builtinEnabled.value)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, viewModel.builtinEnabled.value)
        assertEquals(listOf(false), setting.saved)

        // 模拟刷新/重启：同一存储上重建 VM，状态保持关闭
        val revived = HintListViewModel(repo, setting)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(false, revived.builtinEnabled.value)
    }

    @Test
    fun `保存失败回退到切换前状态并发出失败事件`() = runTest(dispatcher) {
        val setting = FakeBuiltinHintsSetting(initial = true, failWrites = true)
        val viewModel = HintListViewModel(repo, setting)
        val failures = CopyOnWriteArrayList<Unit>()
        // 订阅器用 UNDISPATCHED 前台子协程：立即同步订阅、恢复可被 advanceUntilIdle 推进。
        // （不能用 backgroundScope：其任务被调度器标记为后台，显式 advanceUntilIdle 不会执行它们，
        //   tryEmit 时订阅尚未建立，事件会被静默丢弃。）
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { viewModel.saveFailed.collect { failures += it } }

        viewModel.setBuiltinEnabled(false)
        assertEquals(false, viewModel.builtinEnabled.value) // 乐观跟手
        dispatcher.scheduler.advanceUntilIdle()

        // 写入失败未持久化；UI 收敛回存储实际值（= 切换前的开启态），并收到一次失败事件
        assertEquals(true, viewModel.builtinEnabled.value)
        assertTrue(setting.saved.isEmpty())
        assertEquals(1, failures.size)
        collector.cancel() // 常驻收集器手动收尾，避免 runTest 等待未完成子协程
    }

    @Test
    fun `快速连续切换保存按触发顺序落定-终态无乱序`() = runTest(dispatcher) {
        val setting = FakeBuiltinHintsSetting(initial = true)
        val viewModel = HintListViewModel(repo, setting)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.setBuiltinEnabled(false)
        viewModel.setBuiltinEnabled(true)
        dispatcher.scheduler.advanceUntilIdle()

        // 保存串行且按触发序：先 false 后 true，无旧值覆盖新值；UI 终态与存储一致
        assertEquals(listOf(false, true), setting.saved)
        assertEquals(true, viewModel.builtinEnabled.value)

        // 再来一轮反向快速切换，终态仍收敛到最后意图
        viewModel.setBuiltinEnabled(true) // 同值幂等：不发保存
        viewModel.setBuiltinEnabled(false)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(listOf(false, true, false), setting.saved)
        assertEquals(false, viewModel.builtinEnabled.value)
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
