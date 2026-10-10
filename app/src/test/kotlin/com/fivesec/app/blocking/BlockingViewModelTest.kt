package com.fivesec.app.blocking

import com.fivesec.app.domain.model.InterceptionOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BlockingViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `倒计时未结束时，打开与取消均无效（强制减速带）`() = runTest(dispatcher) {
        val vm = BlockingViewModel("抖音")
        // 推进部分时间但未到 5s
        advanceTimeBy(2_000)
        assertTrue(vm.ui.value is BlockingViewModel.UiState.CountingDown)

        vm.open()   // 倒计时中：应被忽略
        vm.cancel() // 倒计时中：应被忽略
        assertTrue("仍应处于倒计时", vm.ui.value is BlockingViewModel.UiState.CountingDown)
    }

    @Test
    fun `倒计时结束后解锁，可选择打开`() = runTest(dispatcher) {
        val vm = BlockingViewModel("抖音")
        advanceTimeBy(5_500) // 5 秒倒计时结束
        advanceUntilIdle()
        assertTrue(vm.ui.value is BlockingViewModel.UiState.ChoiceUnlocked)

        vm.open()
        assertEquals(InterceptionOutcome.OPENED, (vm.ui.value as BlockingViewModel.UiState.Finished).outcome)
    }

    @Test
    fun `解锁后选择取消先进入已抵制态并携带今日序号（specs-011）`() = runTest(dispatcher) {
        val vm = BlockingViewModel("小红书", resistCountProvider = { 3 })
        advanceTimeBy(5_500)
        advanceUntilIdle()
        vm.cancel()
        val resisted = vm.ui.value
        assertTrue("应处于已抵制态", resisted is BlockingViewModel.UiState.Resisted)
        assertEquals(3, (resisted as BlockingViewModel.UiState.Resisted).count)
    }

    @Test
    fun `已抵制态展示固定时长后落取消终态（specs-011）`() = runTest(dispatcher) {
        val vm = BlockingViewModel("小红书")
        advanceTimeBy(5_500)
        advanceUntilIdle()
        vm.cancel()
        advanceTimeBy(799)
        assertTrue("差 1ms 仍应是已抵制态", vm.ui.value is BlockingViewModel.UiState.Resisted)
        advanceTimeBy(2) // 越过截止时刻（advanceTimeBy 不执行恰在目标时刻的任务）
        assertEquals(InterceptionOutcome.CANCELED, (vm.ui.value as BlockingViewModel.UiState.Finished).outcome)
    }

    @Test
    fun `已抵制态再点打开或取消无效且不重复计数（specs-011）`() = runTest(dispatcher) {
        var providerCalls = 0
        val vm = BlockingViewModel("抖音", resistCountProvider = { providerCalls++; 1 })
        advanceTimeBy(5_500)
        advanceUntilIdle()
        vm.cancel()
        vm.open()   // 已决定：无效
        vm.cancel() // 重复点：无效、不再取号
        assertTrue(vm.ui.value is BlockingViewModel.UiState.Resisted)
        assertEquals("序号提供者只应被调用一次", 1, providerCalls)
        advanceTimeBy(BlockingViewModel.RESIST_DISPLAY_MS + 1)
        assertEquals(InterceptionOutcome.CANCELED, (vm.ui.value as BlockingViewModel.UiState.Finished).outcome)
    }

    @Test
    fun `已抵制态被打断直接按取消收尾不误记中断（specs-011）`() = runTest(dispatcher) {
        val vm = BlockingViewModel("B站")
        advanceTimeBy(5_500)
        advanceUntilIdle()
        vm.cancel()
        vm.markInterrupted() // 展示期间服务被拆：跳过剩余展示，结局不翻案
        assertEquals(InterceptionOutcome.CANCELED, (vm.ui.value as BlockingViewModel.UiState.Finished).outcome)
    }

    @Test
    fun `倒计时中被打断标记为 INTERRUPTED`() = runTest(dispatcher) {
        val vm = BlockingViewModel("B站")
        advanceTimeBy(1_000)
        vm.markInterrupted()
        assertEquals(InterceptionOutcome.INTERRUPTED, (vm.ui.value as BlockingViewModel.UiState.Finished).outcome)
    }
}
