package com.fivesec.app.data.repository

import com.fivesec.app.data.db.TodoDao
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodayTodo
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 待办聚合点（specs/005-daily-todos）：
 *  - 无障碍服务在主线程同步调 [todayTodos] 取覆盖层"今日待办"快照（快照模式，对齐 HintRepository）；
 *  - 待办页经 [observeAll]/add/rename/remove/setEnabled/setCompleted 管理。
 *
 * "每日重置"是惰性求值：完成判定 = lastCompletedDate == today（调用方经 DateUtil 产出 today，
 * 纯逻辑不读系统时钟），跨日自动失效，无任何清理任务。
 */
@Singleton
class TodoRepository @Inject constructor(
    private val todoDao: TodoDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Any()
    private var snapshot: List<Todo> = emptyList() // id 升序 = 创建顺序

    init {
        scope.launch {
            todoDao.observeAll().collect { entries ->
                synchronized(lock) { snapshot = entries }
            }
        }
    }

    /** 覆盖层创建时调用（主线程安全：锁内纯内存映射）；只含启用条目，isDone 按 today 口径映射。 */
    fun todayTodos(today: String): List<TodayTodo> = synchronized(lock) {
        snapshot.filter { it.isEnabled }.map { TodayTodo(it.text, it.lastCompletedDate == today) }
    }

    /** 待办页列表（id 升序，含停用条目；透传 DAO）。 */
    fun observeAll(): Flow<List<Todo>> = todoDao.observeAll()

    /** 新增：校验（trim/空白拒/30 字截断）+ 上限 20；失败返回中文文案的 Result。 */
    suspend fun add(text: String): Result<Unit> {
        val normalized = normalize(text)
            ?: return Result.failure(IllegalArgumentException("待办内容不能为空"))
        if (todoDao.count() >= MAX_TODOS) {
            return Result.failure(IllegalStateException("最多可添加${MAX_TODOS}条待办，请删除后重试"))
        }
        return runCatching { todoDao.insert(Todo(text = normalized)); Unit }
    }

    /** 重命名：同一校验口径，定向更新 text 列（避免覆盖并发发生的勾选/启停）。 */
    suspend fun rename(id: Long, text: String): Result<Unit> {
        val normalized = normalize(text)
            ?: return Result.failure(IllegalArgumentException("待办内容不能为空"))
        return runCatching { todoDao.updateText(id, normalized) }
    }

    suspend fun remove(id: Long) = todoDao.deleteById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = todoDao.setEnabled(id, enabled)

    /** 勾选写 [today]、取消写空串；today 由调用方按当天口径传入（VM 持 TimeProvider）。 */
    suspend fun setCompleted(id: Long, today: String, completed: Boolean) {
        todoDao.setCompletedDate(id, if (completed) today else "")
    }

    /** 与 HintRepository 同一口径：trim 非空白 + 30 字符硬截断；不合法返回 null（不落库）。 */
    private fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_TEXT_LENGTH)
    }

    companion object {
        /** 待办数量上限（添加校验与 UI 名额行共用单一来源）。 */
        const val MAX_TODOS = 20

        /** 标题长度上限（与提示语同一口径）。 */
        const val MAX_TEXT_LENGTH = 30
    }
}
