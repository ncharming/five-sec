package com.fivesec.app.data.repository

import com.fivesec.app.data.db.TodoCompletionDao
import com.fivesec.app.data.db.TodoDao
import com.fivesec.app.data.db.TodoRangeCount
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoCompletion
import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.domain.model.TodayTodo
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
import com.fivesec.app.util.TodoRecurrence
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * 待办聚合点（specs/005-daily-todos；006 重复规则；007 一次性/过期口径；008 完成事件）：
 *  - 无障碍服务在主线程同步调 [todayTodos] 取覆盖层"今日待办"快照（快照模式，对齐 HintRepository）；
 *  - 待办页经 [observeAll]/add/rename/remove/setEnabled/setCompleted/setRecurrence/revive 管理；
 *  - 统计页经 [observeCompletionCountByTodoBetween]/[observeEarliestCompletionDate] 聚合完成历史
 *    （todo_completions 事件表，勾选落一行/取消删当日，统计=流水实时聚合无预聚合表）。
 *
 * "每日重置"是惰性求值：完成判定 = lastCompletedDate == today（调用方经 DateUtil 产出 today，
 * 纯逻辑不读系统时钟），跨日自动失效，无任何清理任务。一次性规则（007）的日期列由本层按注入的
 * [TimeProvider] 产出（仓库约定：时间一律经注入，禁在纯逻辑直接取系统时钟）：
 * createdAt=插入当天（永不改写）；dueDate=规则生效当天（转仅今天/新建/复活时写，转回重复类清空）。
 */
@Singleton
class TodoRepository @Inject constructor(
    private val todoDao: TodoDao,
    private val todoCompletionDao: TodoCompletionDao,
    private val timeProvider: TimeProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Any()
    private var snapshot: List<Todo> = emptyList() // id 升序 = 创建顺序

    init {
        scope.launch {
            todoDao.observeAll().collect { entries ->
                synchronized(lock) { snapshot = entries }
                // 惰性清理（007）：已完成的一次性跨日后物理删除。DELETE 触发 Room 重发 → 再收集 →
                // 无匹配行不再 DELETE，自稳定；无后台任务，纯加载路径顺手清（todos 可删，红线只在事件表）
                todoDao.purgeCompletedOneOffs(today())
            }
        }
    }

    private fun today(): String = DateUtil.todayString(timeProvider.now())

    /** 覆盖层创建时调用（主线程安全：锁内纯内存映射）；只含「启用且今天轮到」条目，
     *  isDone 按 today 口径映射——覆盖层卡片的 D/T 分母、○ 未完成列表、空块隐藏因此自动
     *  只看轮到条目（FR-006/FR-007），服务与 BlockingOverlay 零改动。一次性条目当天计入、
     *  过期/非有效期日天然排除（isDue 口径）。条目按 [overlayOrder] 排序：卡片只取头部，
     *  谁「排在前面」由这里定。 */
    fun todayTodos(today: String): List<TodayTodo> = synchronized(lock) {
        overlayOrder(
            snapshot.filter {
                it.isEnabled && TodoRecurrence.isDue(
                    it.repeatType,
                    it.repeatDays,
                    it.intervalDays,
                    it.lastCompletedDate,
                    it.dueDate,
                    today,
                )
            },
        ).map { TodayTodo(it.text, it.lastCompletedDate == today) }
    }

    /** 待办页列表（id 升序，含停用与过期条目，分区由 VM 派生；透传 DAO）。 */
    fun observeAll(): Flow<List<Todo>> = todoDao.observeAll()

    /** 新增：校验（trim/空白拒/200 字截断 + 规则兜底校验）+ 上限 20；失败返回中文文案的 Result。
     *  [rule] 缺省 = 每天（既有调用零改动）。createdAt=今天；仅今天规则 dueDate=今天、其余空串。 */
    suspend fun add(text: String, rule: TodoRule = TodoRule.DAILY): Result<Unit> {
        val normalized = normalize(text)
            ?: return Result.failure(IllegalArgumentException("待办内容不能为空"))
        val safeRule = normalizeRule(rule).getOrElse { return Result.failure(it) }
        if (todoDao.count() >= MAX_TODOS) {
            return Result.failure(IllegalStateException("最多可添加${MAX_TODOS}条待办，请删除后重试"))
        }
        val today = today()
        val todo = Todo(
            text = normalized,
            repeatType = safeRule.repeatType,
            repeatDays = safeRule.repeatDays,
            intervalDays = safeRule.intervalDays,
            createdAt = today,
            dueDate = if (safeRule.repeatType == TodoRecurrence.REPEAT_ONCE) today else "",
        )
        return runCatching { todoDao.insert(todo); Unit }
    }

    /** 重命名：同一校验口径，定向更新 text 列（避免覆盖并发发生的勾选/启停）。 */
    suspend fun rename(id: Long, text: String): Result<Unit> {
        val normalized = normalize(text)
            ?: return Result.failure(IllegalArgumentException("待办内容不能为空"))
        return runCatching { todoDao.updateText(id, normalized) }
    }

    suspend fun remove(id: Long) = todoDao.deleteById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean) = todoDao.setEnabled(id, enabled)

    /** 修改重复规则：定向更新规则三列 + dueDate（同一语句原子落库，不触碰并发勾选/启停/文本/创建日）；
     *  周几空集拒绝、间隔越界收敛（与 TodoRule 工厂同一口径的兜底）。
     *  dueDate 口径（007）：转「仅今天」= 修改当天（当天即轮到）；转回重复类 = 清空。 */
    suspend fun setRecurrence(id: Long, rule: TodoRule): Result<Unit> {
        val safeRule = normalizeRule(rule).getOrElse { return Result.failure(it) }
        val dueDate = if (safeRule.repeatType == TodoRecurrence.REPEAT_ONCE) today() else ""
        return runCatching {
            todoDao.updateRecurrence(id, safeRule.repeatType, safeRule.repeatDays, safeRule.intervalDays, dueDate)
        }
    }

    /** 过期条目「改为今天」（007）：仅重写有效期日为当天——条目回今日区、当天可勾；
     *  再次跨日未完成会再次过期（失败的存量给自己一条活路，而非迟到补勾）。 */
    suspend fun revive(id: Long) {
        todoDao.setDueDate(id, today())
    }

    /** 规则兜底校验（主拦截在编辑弹窗表单层）：周几至少一天；间隔收敛 2..365；仅今天原样通过。 */
    private fun normalizeRule(rule: TodoRule): Result<TodoRule> = when {
        rule.repeatType == TodoRecurrence.REPEAT_WEEKLY && rule.repeatDays == 0 ->
            Result.failure(IllegalArgumentException("每周至少选择一天"))

        rule.repeatType == TodoRecurrence.REPEAT_INTERVAL ->
            Result.success(rule.copy(intervalDays = TodoRecurrence.coerceIntervalDays(rule.intervalDays)))

        else -> Result.success(rule)
    }

    /** 勾选写 [today]、取消写空串；today 由调用方按当天口径传入（VM 持 TimeProvider）。
     *  双写完成事件（008）：勾选 upsert 一行（todoId+当日+文本快照——快照取勾选当时的文本，
     *  条目事后删除/改名历史不失联）；取消删当日行——(todoId, 当日) 唯一，重勾永不重复计数。
     *  顺序为先 todos 行后事件行：当日 UI 判定以 lastCompletedDate 为准，事件行晚到不影响；
     *  中途崩溃的窗口由下次勾选的 REPLACE 自愈，不引入跨 DAO 事务。 */
    suspend fun setCompleted(id: Long, today: String, completed: Boolean) {
        todoDao.setCompletedDate(id, if (completed) today else "")
        if (completed) {
            val text = todoDao.findById(id)?.text ?: return // 条目已被并发删除：无快照可写，跳过
            todoCompletionDao.upsert(TodoCompletion(todoId = id, todoText = text, completedDate = today))
        } else {
            todoCompletionDao.deleteByTodoAndDate(id, today)
        }
    }

    /** 周期内按条目的完成次数（统计页任务历史二级页；区间 [startDate, endDate) 半开，yyyy-MM-dd 字典序）。 */
    fun observeCompletionCountByTodoBetween(startDate: String, endDate: String): Flow<List<TodoRangeCount>> =
        todoCompletionDao.observeCountByTodoBetween(startDate, endDate)

    /** 最早完成日期（yyyy-MM-dd；无记录为 null）——统计页年档位可选范围与拦截最早事件取更早。 */
    fun observeEarliestCompletionDate(): Flow<String?> = todoCompletionDao.observeEarliestDate()

    /** trim 非空白 + 200 字符硬截断（待办口径；提示语维持 30 字，两者不再同一口径）；不合法返回 null（不落库）。 */
    private fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_TEXT_LENGTH)
    }

    companion object {
        /** 待办数量上限（添加校验与 UI 名额行共用单一来源；今日/过期两区共享，specs/007）。 */
        const val MAX_TODOS = 20

        /** 标题长度上限：待办 200 字（提示语维持 30 字不变）。展示层另有两个更小的口径：待办页单行省略、拦截卡片截前 12 字。 */
        const val MAX_TEXT_LENGTH = 200

        /** 覆盖层卡片排序（用户拍板口径）：第一层类型优先级 仅今天→每N天→每周几→每天
         *  （[TodoRecurrence.overlayPriority]）；第二层同类型内创建日倒序（最新在前）——
         *  createdAt 只有日粒度，同日以 id 倒序补足真实插入顺序（id=自增=创建顺序）；
         *  v7 前老条目 createdAt 空串在同类型内垫底。纯函数零 IO：todayTodos 接线、单测直测。 */
        fun overlayOrder(todos: List<Todo>): List<Todo> = todos.sortedWith(
            compareBy<Todo> { TodoRecurrence.overlayPriority(it.repeatType) }
                .thenByDescending { it.createdAt }
                .thenByDescending { it.id },
        )
    }
}
