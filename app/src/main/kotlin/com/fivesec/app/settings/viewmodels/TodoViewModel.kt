package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
import com.fivesec.app.util.TodoRecurrence
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 待办行展示模型：条目 + 今日完成态 + 今日是否轮到（不轮到 → 行灰显禁勾，specs/006）。 */
data class TodoRow(val todo: Todo, val doneToday: Boolean, val dueToday: Boolean)

/**
 * 待办页状态（specs/005-daily-todos；006 增重复规则）：列表观察 + 增删改/启停/今日勾选/规则转发。
 *
 * `today` 是私有 StateFlow 而非每次现取：完成态与"轮到"都在 combine 里按它求值，
 * ON_RESUME 调 [refreshToday] 覆盖"应用常驻后台跨日"的场景——不引入任何定时器。
 * 可测试性：时间一律经注入的 [TimeProvider]（仓库约定，禁在纯逻辑直接取系统时钟）。
 */
@HiltViewModel
class TodoViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val today = MutableStateFlow(DateUtil.todayString(timeProvider.now()))

    val rows: StateFlow<List<TodoRow>> =
        combine(todoRepository.observeAll(), today) { list, todayString ->
            list.map {
                TodoRow(
                    todo = it,
                    doneToday = it.lastCompletedDate == todayString,
                    dueToday = TodoRecurrence.isDue(
                        it.repeatType,
                        it.repeatDays,
                        it.intervalDays,
                        it.lastCompletedDate,
                        todayString,
                    ),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 页面 ON_RESUME 调用：跨日后重算今日口径（完成态与灰显态随之联动）。 */
    fun refreshToday() {
        today.value = DateUtil.todayString(timeProvider.now())
    }

    fun add(text: String, rule: TodoRule = TodoRule.DAILY) {
        viewModelScope.launch {
            // 失败静默忽略（空白/名额满是 UI 已挡的不可达路径，repository 校验是双保险）；
            // 不打 android.util.Log——纯 JVM 单测未 mock，会直接抛 RuntimeException
            todoRepository.add(text, rule)
        }
    }

    fun rename(id: Long, text: String) {
        viewModelScope.launch {
            todoRepository.rename(id, text)
        }
    }

    /** 规则修改转发（定向 UPDATE；校验失败由 Repository Result 兜底，表单层已主拦截）。 */
    fun setRecurrence(id: Long, rule: TodoRule) {
        viewModelScope.launch { todoRepository.setRecurrence(id, rule) }
    }

    fun remove(id: Long) {
        viewModelScope.launch { todoRepository.remove(id) }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { todoRepository.setEnabled(id, enabled) }
    }

    /** 今日勾选/取消：以 VM 持有的 today 口径写入（与 rows 派生口径严格同源）。 */
    fun setCompleted(id: Long, completed: Boolean) {
        viewModelScope.launch { todoRepository.setCompleted(id, today.value, completed) }
    }
}
