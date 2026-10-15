package com.fivesec.app.reminder

import com.fivesec.app.domain.model.Todo
import com.fivesec.app.util.TodoRecurrence
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 待办提醒排程纯逻辑（specs/010-todo-reminders；013 起单条多时刻）：「下一次提醒时刻」与
 * 「到点该不该响」的唯一判定源。
 *
 * 为什么是纯函数且零 Android import：与 [TodoRecurrence]/DateUtil 同层的口径——被三方消费同一实现
 * （协调器重排、receiver 响前查库、单测判定表），零系统时钟读取（now/ZoneId 一律由调用方注入，
 * CI UTC 与真机行为逐位一致）。核心拍板口径（用户质询确认）：
 *  - 只在「启用 && 轮到 && 当天未完成」的日子响（isDue 同源三处不漂移）；
 *  - 绝不补响：任何已过时刻静默跳过，排程只面向未来；
 *  - 间隔规则拖延顺延期每天轮到 → 每天连响直到完成（是特性不是 bug）；
 *  - 防御优先：脏数据（非法时刻/空规则/非法日期）宁可返回 null 不排程，不崩溃。
 *
 * 013 多时刻泛化：[Todo.reminderTimes] 为 HH:mm 逗号分隔列表（规范形=升序去重，上限
 * [MAX_REMINDER_TIMES]），排程与到点过滤按「逐时刻独立判定、当日取最早未来时刻」泛化——
 * 存量单值即单元素列表，行为逐位不变；「单次」新建默认 [DEFAULT_ONCE_REMINDER_TIMES]
 * 三时刻（当天 20/21/22 整点，specs/013 的强制到期提醒）。
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

    /**
     * 单条待办的提醒时刻条数上限（013）：与「单次默认三时刻」同量级——提醒是助推不是轰炸，
     * 同时给 CSV 编码与弹窗 chips 一个确定上界（调大只改此常量）。
     */
    const val MAX_REMINDER_TIMES = 3

    /** 单次待办默认提醒时刻（013：「必须今天完成」的强制到期提醒）：当天 20:00、21:00、22:00 整点三连响。 */
    val DEFAULT_ONCE_REMINDER_TIMES = listOf("20:00", "21:00", "22:00")

    /** [DEFAULT_ONCE_REMINDER_TIMES] 的存储编码（规范形 CSV）：弹窗预填与「未动过」比对共用单一来源。 */
    val DEFAULT_ONCE_REMINDER_TIMES_RAW: String = DEFAULT_ONCE_REMINDER_TIMES.joinToString(",")

    /** 解析存储编码为时刻列表（防御读：剔空段/非法段，去重升序）——脏数据兜底为「尽量可用」而非崩溃。 */
    fun parseReminderTimes(raw: String): List<String> =
        raw.split(",")
            .map { it.trim() }
            .filter { it.matches(TIME_PATTERN) }
            .distinct()
            .sorted()

    /**
     * 校验存储编码（表单/仓库双层共用同一口径）：空串=清除，合法；非空须每段 HH:mm
     * 且段数 ≤ [MAX_REMINDER_TIMES]（重复段不拒——由 [normalizeReminderTimes] 收编）。
     */
    fun isValidReminderTimes(raw: String): Boolean =
        raw.isEmpty() || raw.split(",").let { segments ->
            segments.size <= MAX_REMINDER_TIMES && segments.all { it.trim().matches(TIME_PATTERN) }
        }

    /** 归一化存储编码（写入的唯一形态）：解析 → 去重升序 → 重拼 CSV；空列表落空串=无提醒。 */
    fun normalizeReminderTimes(raw: String): String = parseReminderTimes(raw).joinToString(",")

    /**
     * 规则段切换时提醒时刻的联动（013 弹窗纯投影，交互逻辑下沉到此只为可单测）：
     *  - 切到「单次」且当前无时刻 → 预填默认三时刻（返回 autoFilled=true：用户可见、可改、可清）；
     *  - 离开「单次」且时刻仍是**原封未动**的预填 → 还原为空（三连响是单次的到期语义，不泄给重复类）；
     *  - 其余（含用户增/删/改过的任何时刻）原样保留——预填只在「未动过」时让位，用户输入永不被吞。
     */
    fun timesOnRuleSwitch(
        oldType: Int,
        newType: Int,
        currentRaw: String,
        currentAutoFilled: Boolean,
    ): Pair<String, Boolean> = when {
        newType == TodoRecurrence.REPEAT_ONCE && currentRaw.isEmpty() ->
            DEFAULT_ONCE_REMINDER_TIMES_RAW to true

        oldType == TodoRecurrence.REPEAT_ONCE && newType != TodoRecurrence.REPEAT_ONCE &&
            currentAutoFilled && currentRaw == DEFAULT_ONCE_REMINDER_TIMES_RAW ->
            "" to false

        else -> currentRaw to false
    }

    /**
     * 到点响铃判定（receiver 响前查库的唯一过滤）：
     * 触发时刻命中列表任一时刻 且 启用 且 reminderDay 当天轮到 且 reminderDay 当天未完成。
     *
     * [reminderDay]/[minute] 取自**排程时刻**（intent extra）而非「当前时间」：非精确降级晚到数分钟
     * 仍按计划分钟的口径聚合；当天完成的最后一刻变更也被最新库态吸收（质询拍板：响前查库）——
     * 勾掉完成即静默，后续时刻一个都不响（013 验收口径）。
     */
    fun isDueForMinute(todo: Todo, reminderDay: String, minute: String): Boolean =
        todo.isEnabled &&
            parseReminderTimes(todo.reminderTimes).contains(minute) &&
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
     * 多时刻口径：同一轮到日内取**最早的未来时刻**；今天已过的时刻静默跳过（不补响），全部已过
     * 则顺延到下一个轮到日的最早时刻。单次：有效期日 < 今天 → null（过期不响）。
     */
    fun nextTriggerAt(todo: Todo, nowMillis: Long, zone: ZoneId): Long? {
        if (!todo.isEnabled) return null
        // parseReminderTimes 已过 TIME_PATTERN，LocalTime.parse 恒成功；mapNotNull 是防御冗余（脏数据宁可 null 不崩）
        val times = parseReminderTimes(todo.reminderTimes)
            .mapNotNull { runCatching { LocalTime.parse(it) }.getOrNull() }
        if (times.isEmpty()) return null
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        return when (todo.repeatType) {
            TodoRecurrence.REPEAT_ONCE -> nextOnceTriggerAt(todo, times, today, nowMillis, zone)
            else -> nextRepeatingTriggerAt(todo, times, today, nowMillis, zone)
        }
    }

    /** 全量条目中最早的下一次提醒时刻（全局单一「下一响」闹钟的排程目标）；全空 → null。 */
    fun nextTriggerAt(todos: List<Todo>, nowMillis: Long, zone: ZoneId): Long? =
        todos.asSequence().mapNotNull { nextTriggerAt(it, nowMillis, zone) }.minOrNull()

    private fun nextOnceTriggerAt(
        todo: Todo,
        times: List<LocalTime>,
        today: LocalDate,
        nowMillis: Long,
        zone: ZoneId,
    ): Long? {
        val due = runCatching { LocalDate.parse(todo.dueDate) }.getOrNull() ?: return null
        if (due < today) return null // 过期（或已过完成清理窗口）不响
        // 今天：已完成静默（勾掉即整条静默，余下时刻全部取消——013 验收口径）
        if (due == today && todo.lastCompletedDate == today.toString()) return null
        // 最早**未来**候选：未来日候选天然 > now；== 今天则挡已过时刻（不补响拍板口径，宁可少提醒）
        return times.asSequence()
            .map { due.atTime(it).atZone(zone).toInstant().toEpochMilli() }
            .filter { it > nowMillis }
            .minOrNull()
    }

    private fun nextRepeatingTriggerAt(
        todo: Todo,
        times: List<LocalTime>,
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
                // 今天且已完成：整天静默（勾完当天不响）；其余取该日最早未来时刻，全已过则继续扫描下一天
                val completedToday = offset == 0 && todo.lastCompletedDate == todayString
                if (!completedToday) {
                    val candidate = times.asSequence()
                        .map { cursor.atTime(it).atZone(zone).toInstant().toEpochMilli() }
                        .filter { it > nowMillis } // 未来日候选恒过；今天挡已过时刻（不补响）
                        .minOrNull()
                    if (candidate != null) return candidate
                }
            }
            cursor = cursor.plusDays(1)
        }
        return null // 视界内无轮到日（周几空集等脏数据）：静默不排程
    }
}
