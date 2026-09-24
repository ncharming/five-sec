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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 待办行展示模型：条目 + 今日完成态 + 今日是否轮到（不轮到 → 行灰显禁勾，specs/006）
 *  + 副行日期（今日区=创建日「—」兜底；过期区=有效期日，specs/007）。 */
data class TodoRow(val todo: Todo, val doneToday: Boolean, val dueToday: Boolean, val dateLabel: String)

/** 待办页两区状态（specs/007）：今日区（一切未过期，id 升序）+ 过期区（一次性失败存量，有效期日升序）。 */
data class TodoUiState(val todayRows: List<TodoRow>, val expiredRows: List<TodoRow>)

/**
 * 待办页状态（specs/005；006 重复规则；007 一次性/两区分区）：
 * 列表观察 + 增删改/启停/今日勾选/规则转发/过期复活。
 *
 * `today` 是 VM 持有的 StateFlow（私有可写 + [today] 只读暴露）而非每次现取：完成态、"轮到"、
 * 过期分区都在 combine 里按它求值，ON_RESUME 调 [refreshToday] 覆盖"应用常驻后台跨日"的场景——
 * 不引入任何定时器。
 * 僵尸行（一次性已完成且完成日≠今天）在此过滤兜底：物理删除由仓库惰性清理收尾，两道防线。
 * 可测试性：时间一律经注入的 [TimeProvider]（仓库约定，禁在纯逻辑直接取系统时钟）。
 */
@HiltViewModel
class TodoViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {

    private val _today = MutableStateFlow(DateUtil.todayString(timeProvider.now()))

    /** 今日口径（yyyy-MM-dd）只读暴露：编辑弹窗「每N天 → 下次执行」展示消费，与灰显派发严格同源。 */
    val today: StateFlow<String> = _today.asStateFlow()

    val uiState: StateFlow<TodoUiState> =
        combine(todoRepository.observeAll(), _today) { list, todayString ->
            val todayRows = mutableListOf<TodoRow>()
            val expiredRows = mutableListOf<TodoRow>()
            list.forEach { todo ->
                when {
                    // 僵尸行：一次性已完成且完成日不是今天（跨日待清理）——不进任何区，等仓库物理删除
                    todo.repeatType == TodoRecurrence.REPEAT_ONCE &&
                        todo.lastCompletedDate.isNotEmpty() &&
                        todo.lastCompletedDate != todayString -> Unit

                    TodoRecurrence.isExpired(todo.repeatType, todo.dueDate, todo.lastCompletedDate, todayString) ->
                        expiredRows += TodoRow(
                            todo = todo,
                            doneToday = false,
                            dueToday = false,
                            dateLabel = todo.dueDate, // 过期区副行=有效期日（哪天失败的）
                        )

                    else -> todayRows += TodoRow(
                        todo = todo,
                        doneToday = todo.lastCompletedDate == todayString,
                        dueToday = TodoRecurrence.isDue(
                            todo.repeatType,
                            todo.repeatDays,
                            todo.intervalDays,
                            todo.lastCompletedDate,
                            todo.dueDate,
                            todayString,
                        ),
                        dateLabel = todo.createdAt.ifEmpty { UNKNOWN_DATE }, // 老条目（v7 前）无创建日 → 「—」
                    )
                }
            }
            TodoUiState(
                todayRows = todayRows,
                expiredRows = expiredRows.sortedBy { it.todo.dueDate }, // 有效期日升序=最早失败在前；同日稳定保 id 序
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, TodoUiState(emptyList(), emptyList()))

    /** 页面 ON_RESUME 调用：跨日后重算今日口径（完成态、灰显态与过期分区随之联动）。 */
    fun refreshToday() {
        _today.value = DateUtil.todayString(timeProvider.now())
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

    /** 过期条目「改为今天」转发（specs/007）：重写有效期日，条目回今日区。 */
    fun revive(id: Long) {
        viewModelScope.launch { todoRepository.revive(id) }
    }

    fun remove(id: Long) {
        viewModelScope.launch {
            todoRepository.remove(id)
        }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch {
            todoRepository.setEnabled(id, enabled)
        }
    }

    /** 今日勾选/取消：以 VM 持有的 today 口径写入（与 uiState 派生口径严格同源）。 */
    fun setCompleted(id: Long, completed: Boolean) {
        viewModelScope.launch { todoRepository.setCompleted(id, _today.value, completed) }
    }

    companion object {
        /** 老数据（v7 迁移回填空串）的创建日占位——展示「—」，不伪造迁移日（specs/007 拍板口径）。 */
        const val UNKNOWN_DATE = "—"
    }
}
