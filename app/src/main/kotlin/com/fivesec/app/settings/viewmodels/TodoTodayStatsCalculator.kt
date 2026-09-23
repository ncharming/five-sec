package com.fivesec.app.settings.viewmodels

import com.fivesec.app.domain.model.Todo
import com.fivesec.app.util.TodoRecurrence

/**
 * 统计页「今日任务」三数（specs/008-todo-stats，纯函数无 Android 依赖）：
 *  - [total] 任务 = 今天轮到且启用——与覆盖层 D/T 分母同源谓词（isEnabled && isDue），两处口径永不漂移；
 *  - [completed] 完成 = 其中今天已勾选（lastCompletedDate == today）；
 *  - [expired] 过期 = 一次性过期条目数（含停用，007 口径）。
 *
 * 三个数互不重叠、各说各话：间隔条目完成当天进入灰显期不计入任务/完成（D/T 同行为），
 * 但完成事件照写——今日三数与历史计数是两套口径，别混。
 */
data class TodoTodayStats(val total: Int, val completed: Int, val expired: Int)

object TodoTodayStatsCalculator {

    fun compute(rows: List<Todo>, today: String): TodoTodayStats {
        val due = rows.filter {
            it.isEnabled && TodoRecurrence.isDue(
                it.repeatType,
                it.repeatDays,
                it.intervalDays,
                it.lastCompletedDate,
                it.dueDate,
                today,
            )
        }
        val completed = due.count { it.lastCompletedDate == today }
        val expired = rows.count {
            TodoRecurrence.isExpired(it.repeatType, it.dueDate, it.lastCompletedDate, today)
        }
        return TodoTodayStats(total = due.size, completed = completed, expired = expired)
    }
}
