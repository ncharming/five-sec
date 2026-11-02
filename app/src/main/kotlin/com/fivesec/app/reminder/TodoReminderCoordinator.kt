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
import kotlinx.coroutines.delay
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
 * 并发口径（2026-11 修正注释）：注入 scope 为多线程 IO 池，收集协程与 rescheduleNow 可能交错——
 * 末端 last-write-wins 可能短暂以旧快照覆盖新闹钟，响前查库 + 链式重排兜底自愈，无持久错排。
 *
 * 收集协程带自愈重试（2026-11）：此前异常经 handler 记日志后协程即终结，一次 Room 异常后所有
 * todos 变更不再触发重排（仅靠进 App/下一响自愈）——现在 runCatching + 退避后重订阅，链路
 * 自恢复；DB 异常/环境拆除（Robolectric 会实例化真实 Application，后台 Room 收集线程随环境
 * 销毁会抛）绝不毒化进程，与 FiveSecApp.ensureSeeded 的「宁可丢一次重排不崩」同一口径。
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
            while (true) {
                val failed = try {
                    todoRepository.observeAll().collect { todos -> rescheduleWith(todos) }
                    false // 正常返回（流完成，理论不可达）：立即重建
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    Log.e(TAG, "reminder collector crashed, retrying", t)
                    true
                }
                if (failed) delay(RETRY_BACKOFF_MS) // 退避防异常风暴（如库被持续锁死）
            }
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

        /** 收集器崩溃后的重建退避：异常风暴（库锁死/环境拆除中）别打满 CPU 与日志。 */
        private const val RETRY_BACKOFF_MS = 10_000L
    }
}
