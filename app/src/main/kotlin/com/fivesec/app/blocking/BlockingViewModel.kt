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
 * 拦截页状态机（强制 5 秒减速带 + specs/011 取消成功态）：
 *  CountingDown(remaining) —— 倒计时进行中，按钮锁定
 *  ChoiceUnlocked          —— 倒计时结束，可打开/取消
 *  Resisted(count)         —— 已选取消（specs/011）：展示「已抵制」正反馈约 0.8s 后落终态
 *  Finished(outcome)       —— 终态
 *
 * 不变量：OPENED/CANCELED 只能从 ChoiceUnlocked 进入；INTERRUPTED 可随时进入——
 * **除非已在 [UiState.Resisted]**：抵制一旦决定结局锁定为 CANCELED，打断只提前收尾
 * （跳过剩余成功态展示），绝不翻案为 INTERRUPTED——用户已完成锻炼并做出选择，
 * 事件语义（exerciseCompleted=true）不被反馈层改写。
 *
 * [resistCountProvider] 在 cancel() 时同步求值，返回含本次的今日抵制序号
 * （仓库内存镜像，见 InterceptionRepository.nextResistedOrdinal）；默认 { 1 } 仅供测试便利。
 */
class BlockingViewModel(
    val appLabel: String,
    private val resistCountProvider: () -> Int = { 1 },
) : ViewModel() {

    sealed interface UiState {
        data class CountingDown(val remaining: Int) : UiState
        data object ChoiceUnlocked : UiState
        data class Resisted(val count: Int) : UiState
        data class Finished(val outcome: InterceptionOutcome) : UiState
    }

    private val _ui = MutableStateFlow<UiState>(UiState.CountingDown(Exercise.DURATION_SECONDS))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var countdownJob: Job? = null
    private var resistJob: Job? = null

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

    /** 选择取消（specs/011）：先入成功态展示正反馈，0.8s 后落 CANCELED 终态。 */
    fun cancel() {
        val current = _ui.value
        if (current !is UiState.ChoiceUnlocked) return
        countdownJob?.cancel()
        _ui.value = UiState.Resisted(resistCountProvider())
        resistJob = viewModelScope.launch {
            delay(RESIST_DISPLAY_MS)
            _ui.value = UiState.Finished(InterceptionOutcome.CANCELED)
        }
    }

    /** 倒计时被打断（用户离开）时由 Activity 生命周期调用。 */
    fun markInterrupted() = finish(InterceptionOutcome.INTERRUPTED)

    private fun finish(outcome: InterceptionOutcome) {
        val current = _ui.value
        if (current is UiState.Finished) return
        if (current is UiState.Resisted) {
            // 已决定取消：打断只提前收尾（跳过成功态余下展示），open() 无效——结局不翻案
            if (outcome == InterceptionOutcome.INTERRUPTED) {
                resistJob?.cancel()
                _ui.value = UiState.Finished(InterceptionOutcome.CANCELED)
            }
            return
        }
        // 强制减速带：打开/取消仅在解锁后允许
        if (outcome != InterceptionOutcome.INTERRUPTED && current !is UiState.ChoiceUnlocked) return
        countdownJob?.cancel()
        _ui.value = UiState.Finished(outcome)
    }

    companion object {
        private const val DELAY_MS = 1_000L

        /** 成功态展示时长（specs/011）：与去抖同量级的「体感一瞬」——看得清第 N 次、不觉得被拦路。 */
        const val RESIST_DISPLAY_MS = 800L
    }
}
