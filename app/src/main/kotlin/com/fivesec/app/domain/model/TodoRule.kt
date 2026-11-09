package com.fivesec.app.domain.model

import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek

/**
 * 重复规则值类型（specs/006-recurring-todos；007 增「仅今天」；020 起单次带可选有效期日）：
 * 待办"今天轮不轮到"的四选一规则。
 *
 * 字段与 todos 表规则列一一对应（repeatType/repeatDays/intervalDays + 单次的 dueDate），直接落库/
 * 定向更新，不做聪明转换——判定语义集中在 [TodoRecurrence]（util 纯函数，VM 灰显派生与 Repository
 * 快照过滤共用同一实现，口径不漂移）。
 *
 * [dueDate]（020）仅 [TodoRecurrence.REPEAT_ONCE] 有语义：编辑弹窗选定哪天轮到（空串 = 由仓库落
 * 保存当天，即 007 的「仅今天」缺省形态）。随规则一起参与 data class 相等——编辑弹窗据此判定
 * 「规则没变不发定向 UPDATE」，只改日期也会正确触发 updateRecurrence。
 */
data class TodoRule(
    val repeatType: Int,
    val repeatDays: Int, // 位掩码 bit0=周一 … bit6=周日；仅 repeatType=周几 有语义
    val intervalDays: Int, // 间隔天数 N（2..365）；仅 repeatType=间隔 有语义
    val dueDate: String = "", // 单次有效期日 yyyy-MM-dd（020）；空串 = 仓库落保存当天；重复类恒空串
) {
    companion object {
        /** 缺省规则：每天——specs/005 存量条目经 v6 迁移即此形态，行为逐位一致零感知。 */
        val DAILY = TodoRule(TodoRecurrence.REPEAT_DAILY, 0, 0)

        /** 周几规则：空集是非法输入（"至少选择一天"），表单主拦截 + Repository 兜底校验在先。 */
        fun weekly(days: Set<DayOfWeek>): TodoRule =
            TodoRule(TodoRecurrence.REPEAT_WEEKLY, TodoRecurrence.weeklyMask(days), 0)

        /** 间隔规则：越界收敛到 2..365（滚动节奏，判定见 TodoRecurrence.isDue）。 */
        fun interval(days: Int): TodoRule =
            TodoRule(TodoRecurrence.REPEAT_INTERVAL, 0, TodoRecurrence.coerceIntervalDays(days))

        /** 单次规则（specs/020）：[dueDate] 当天轮到；非法格式由 Repository 兜底拒（表单主拦截在先）。 */
        fun once(dueDate: String): TodoRule = TodoRule(TodoRecurrence.REPEAT_ONCE, 0, 0, dueDate)

        /** 单次规则的缺省形态（specs/007）：dueDate 空串 = 仓库落保存当天（当天即轮到）。 */
        val ONCE = TodoRule(TodoRecurrence.REPEAT_ONCE, 0, 0)
    }
}
