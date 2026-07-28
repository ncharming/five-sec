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
    fun `解锁后选择取消`() = runTest(dispatcher) {
        val vm = BlockingViewModel("小红书")
        advanceTimeBy(5_500)
        advanceUntilIdle()
        vm.cancel()
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
