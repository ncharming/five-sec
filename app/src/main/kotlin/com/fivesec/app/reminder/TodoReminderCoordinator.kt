package com.fivesec.app.reminder

import android.util.Log
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.util.TimeProvider
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 提醒重排协调器（specs/010）：「下一响」的唯一维护者。
 *
 * 为什么监听 observeAll 而非在每个写点手工触发：任何 todos 表变更（改时刻/规则/启停/删除/复活/
 * 勾选完成/惰性清理）都会经 Room 重发，收集器统一重排——无遗漏角落、无状态同步问题；
 * 勾完当天完成（lastCompletedDate=今天）→ Planner 对今天返回 null → 自动排到下个轮到日。
 *
 * [rescheduleNow] 走库直读（allTodosOnce）而非内存快照：开机/时间变更广播到达时，仓库内收集器
 * 可能尚未装载完成，库直读是就绪时序上唯一可靠的数据源。供 BootReceiver 与 FiveSecApp 启动兜底。
 * 串行执行：收集协程天然串行；rescheduleNow 亦在同一注入 scope 排队，无并发排程竞态。
 *
 * 收集协程显式挂 [CoroutineExceptionHandler]（记日志不冒泡）：重排属尽力而为——DB 异常/环境拆除
 * （Robolectric 会实例化真实 Application，后台 Room 收集线程随环境销毁会抛）绝不毒化进程；
 * 与 FiveSecApp.ensureSeeded 的「runCatching 包住、宁可丢一次重排不崩」同一口径。
 */
@Singleton
class TodoReminderCoordinator @Inject constructor(
    private val todoRepository: TodoRepository,
    private val scheduler: TodoReminderScheduler,
    private val timeProvider: TimeProvider,
    private val scope: CoroutineScope, // AppModule 提供的应用级作用域
) {
    private val failureHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "reminder reschedule collector failed", throwable)
    }

    init {
        scope.launch(failureHandler) {
            todoRepository.observeAll().collect { todos -> rescheduleWith(todos) }
        }
    }

    /** 全量重排（开机/时间变更/进 App 兜底/receiver 触发后排下一响）。 */
    suspend fun rescheduleNow() {
        rescheduleWith(todoRepository.allTodosOnce())
    }

    private suspend fun rescheduleWith(todos: List<Todo>) {
        val trigger = TodoReminderPlanner.nextTriggerAt(todos, timeProvider.now(), ZoneId.systemDefault())
        if (trigger == null) scheduler.cancel() else scheduler.scheduleNext(trigger)
    }

    companion object {
        private const val TAG = "TodoReminder"
    }
}
