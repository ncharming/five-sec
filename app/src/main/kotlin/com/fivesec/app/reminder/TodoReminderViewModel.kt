package com.fivesec.app.reminder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 全屏提醒页状态（specs/010 决策 14）：FSI 通知携带的 ids 装载 → 展示前防御过滤（仍启用且当天
 * 未完成——跨进程窗口期的脏 ids/已处理条目就地吸收）→ 逐条勾完成（复用仓库 setCompleted 双写：
 * todos 行 + todo_completions 事件，与待办页勾选完全同一入口，「勾选仅在待办页」口径扩展至此）。
 * 勾完即从列表移除，全空 = 全部完成（Activity 观察到即自动关页停铃）。
 */
@HiltViewModel
class TodoReminderViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    /** 提醒页行（纯展示值：id + 全文；不复用 TodoRow——提醒页无灰显/分区语义）。 */
    data class ReminderRow(val id: Long, val text: String)

    /** loaded=false：装载中（ids 空数组防呆也置 true——空提醒页走自动关闭路径）。 */
    data class ReminderUiState(val loaded: Boolean = false, val rows: List<ReminderRow> = emptyList())

    private val _state = MutableStateFlow(ReminderUiState())
    val state: StateFlow<ReminderUiState> = _state.asStateFlow()

    /** 装载（onCreate 调一次；重复调用防重入——FSI 重发场景同一 Activity 实例不重置列表）。 */
    fun load(ids: List<Long>) {
        if (_state.value.loaded) return
        viewModelScope.launch {
            val today = DateUtil.todayString(timeProvider.now())
            val rows = todoRepository.findByIds(ids)
                .filter { it.isEnabled && it.lastCompletedDate != today }
                .map { ReminderRow(it.id, it.text) }
            _state.value = ReminderUiState(loaded = true, rows = rows)
        }
    }

    /** 勾选完成（与待办页同款双写：定向 UPDATE + 完成事件 upsert）；成功即从列表移除。 */
    fun complete(id: Long) {
        viewModelScope.launch {
            val today = DateUtil.todayString(timeProvider.now())
            todoRepository.setCompleted(id, today, completed = true)
            _state.update { it.copy(rows = it.rows.filterNot { row -> row.id == id }) }
        }
    }

    /** 超时收底通知带参用：当前未处理条目的 ids。 */
    fun pendingIds(): List<Long> = _state.value.rows.map { it.id }
}
