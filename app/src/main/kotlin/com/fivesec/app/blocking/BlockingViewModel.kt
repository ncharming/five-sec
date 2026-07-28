package com.fivesec.app.blocking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.domain.model.Exercise
import com.fivesec.app.domain.model.InterceptionOutcome
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 拦截页状态机（强制 5 秒减速带）：
 *  CountingDown(remaining) —— 倒计时进行中，按钮锁定
 *  ChoiceUnlocked          —— 倒计时结束，可打开/取消
 *  Finished(outcome)       —— 终态
 *
 * 不变量：OPENED/CANCELED 只能从 ChoiceUnlocked 进入；INTERRUPTED 可随时进入。
 */
class BlockingViewModel(val appLabel: String) : ViewModel() {

    sealed interface UiState {
        data class CountingDown(val remaining: Int) : UiState
        data object ChoiceUnlocked : UiState
        data class Finished(val outcome: InterceptionOutcome) : UiState
    }

    private val _ui = MutableStateFlow<UiState>(UiState.CountingDown(Exercise.DURATION_SECONDS))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var countdownJob: Job? = null

    init {
        countdownJob = viewModelScope.launch {
            for (n in (Exercise.DURATION_SECONDS - 1) downTo 0) {
                delay(DELAY_MS)
                _ui.value = UiState.CountingDown(n)
            }
            _ui.value = UiState.ChoiceUnlocked
        }
    }

    fun open() = finish(InterceptionOutcome.OPENED)

    fun cancel() = finish(InterceptionOutcome.CANCELED)

    /** 倒计时被打断（用户离开）时由 Activity 生命周期调用。 */
    fun markInterrupted() = finish(InterceptionOutcome.INTERRUPTED)

    private fun finish(outcome: InterceptionOutcome) {
        val current = _ui.value
        if (current is UiState.Finished) return
        // 强制减速带：打开/取消仅在解锁后允许
        if (outcome != InterceptionOutcome.INTERRUPTED && current !is UiState.ChoiceUnlocked) return
        countdownJob?.cancel()
        _ui.value = UiState.Finished(outcome)
    }

    companion object {
        private const val DELAY_MS = 1_000L
    }
}
