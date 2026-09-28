package com.fivesec.app.reminder

import com.fivesec.app.domain.model.Todo
import com.fivesec.app.util.TodoRecurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 待办提醒排程纯逻辑（specs/010-todo-reminders）：「下一次提醒时刻」与「到点该不该响」的唯一判定源。
 *
 * 为什么是纯函数且零 Android import：与 [TodoRecurrence]/DateUtil 同层的口径——被三方消费同一实现
 * （协调器重排、receiver 响前查库、单测判定表），零系统时钟读取（now/ZoneId 一律由调用方注入，
 * CI UTC 与真机行为逐位一致）。核心拍板口径（用户质询确认）：
 *  - 只在「启用 && 轮到 && 当天未完成」的日子响（isDue 同源三处不漂移）；
 *  - 绝不补响：任何已过时刻静默跳过，排程只面向未来；
 *  - 间隔规则拖延顺延期每天轮到 → 每天连响直到完成（是特性不是 bug）；
 *  - 防御优先：脏数据（非法时刻/空规则/非法日期）宁可返回 null 不排程，不崩溃。
 */
object TodoReminderPlanner {

    /**
     * 合法提醒时刻：HH:mm 24 小时制（秒恒 00 不入存）。空串合法=清除提醒。
     * matches() 是全匹配无需锚点；raw string 尾接 `$` 会破坏 Kotlin 模板解析（踩坑记录）。
     */
    private val TIME_PATTERN = Regex("""([01]\d|2[0-3]):[0-5]\d""")

    /**
     * 逐日扫描视界（天）：≥ 间隔上限 365 + 周几/兜底余量。视界内找不到轮到日（如周几空集脏数据）
     * 返回 null——最坏情况不排程，静默失败。
     */
    const val SCAN_HORIZON_DAYS = 370L

    /** 校验提醒时刻（表单/仓库双层共用同一口径；空串=清除，合法）。 */
    fun isValidReminderTime(time: String): Boolean = time.isEmpty() || time.matches(TIME_PATTERN)

    /**
     * 到点响铃判定（receiver 响前查库的唯一过滤）：
     * 时刻匹配（==minute）且 启用 且 reminderDay 当天轮到 且 reminderDay 当天未完成。
     *
     * [reminderDay]/[minute] 取自**排程时刻**（intent extra）而非「当前时间」：非精确降级晚到数分钟
     * 仍按计划分钟的口径聚合；当天完成的最后一刻变更也被最新库态吸收（质询拍板：响前查库）。
     */
    fun isDueForMinute(todo: Todo, reminderDay: String, minute: String): Boolean =
        todo.isEnabled &&
            todo.reminderTime == minute &&
            todo.lastCompletedDate != reminderDay &&
            TodoRecurrence.isDue(
                todo.repeatType,
                todo.repeatDays,
                todo.intervalDays,
                todo.lastCompletedDate,
                todo.dueDate,
                reminderDay,
            )

    /**
     * 单条待办的下次提醒时刻（epoch millis）；null = 无需排程。
     *
     * 单次：有效期日 < 今天 → null（过期不响）；== 今天 → 今天@HH:mm（时刻已过或已完成 → null）；
     * > 今天（防御分支：revive 后时钟回拨等脏形态）→ 该日@HH:mm。
     * 重复类：自今天逐日扫描——不轮到跳过；今天且已完成跳过（勾完当天静默）；今天且时刻 ≤ now
     * 跳过（不补响）；命中即该日@HH:mm。
     */
    fun nextTriggerAt(todo: Todo, nowMillis: Long, zone: ZoneId): Long? {
        if (!todo.isEnabled) return null
        if (!isValidReminderTime(todo.reminderTime) || todo.reminderTime.isEmpty()) return null
        val time = LocalTime.parse(todo.reminderTime) // isValidReminderTime 已挡非法，此处恒成功
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (todo.repeatType) {
            TodoRecurrence.REPEAT_ONCE -> nextOnceTriggerAt(todo, time, today, nowMillis, zone)
            else -> nextRepeatingTriggerAt(todo, time, today, nowMillis, zone)
        }
    }

    /** 全量条目中最早的下一次提醒时刻（全局单一「下一响」闹钟的排程目标）；全空 → null。 */
    fun nextTriggerAt(todos: List<Todo>, nowMillis: Long, zone: ZoneId): Long? =
        todos.asSequence().mapNotNull { nextTriggerAt(it, nowMillis, zone) }.minOrNull()

    private fun nextOnceTriggerAt(
        todo: Todo,
        time: LocalTime,
        today: LocalDate,
        nowMillis: Long,
        zone: ZoneId,
    ): Long? {
        val due = runCatching { LocalDate.parse(todo.dueDate) }.getOrNull() ?: return null
        if (due < today) return null // 过期（或已过完成清理窗口）不响
        val candidate = due.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (due == today) {
            // 今天：已完成静默；时刻已过不补响（拍板口径，宁可少提醒）
            if (todo.lastCompletedDate == today.toString()) return null
            return candidate.takeIf { it > nowMillis }
        }
        return candidate
    }

    private fun nextRepeatingTriggerAt(
        todo: Todo,
        time: LocalTime,
        today: LocalDate,
        nowMillis: Long,
        zone: ZoneId,
    ): Long? {
        val todayString = today.toString()
        var cursor = today
        repeat(SCAN_HORIZON_DAYS.toInt()) { offset ->
            val due = TodoRecurrence.isDue(
                todo.repeatType,
                todo.repeatDays,
                todo.intervalDays,
                todo.lastCompletedDate,
                todo.dueDate,
                cursor.toString(),
            )
            if (due) {
                val candidate = cursor.atTime(time).atZone(zone).toInstant().toEpochMilli()
                val skipToday = offset == 0 &&
                    (todo.lastCompletedDate == todayString || candidate <= nowMillis)
                if (!skipToday) return candidate
            }
            cursor = cursor.plusDays(1)
        }
        return null // 视界内无轮到日（周几空集等脏数据）：静默不排程
    }
}
