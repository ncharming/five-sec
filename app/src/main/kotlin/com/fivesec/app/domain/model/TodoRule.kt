package com.fivesec.app.domain.model

import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek

/**
 * 重复规则值类型（specs/006-recurring-todos；007 增「仅今天」）：待办"今天轮不轮到"的四选一规则。
 *
 * Int 字段与 todos 表规则列一一对应（repeatType/repeatDays/intervalDays，Room v7 另有 dueDate 列
 * 由仓库按当天写入），直接落库/定向更新，不做聪明转换——判定语义集中在 [TodoRecurrence]（util 纯函数，
 * VM 灰显派生与 Repository 快照过滤共用同一实现，口径不漂移）。
 */
data class TodoRule(
    val repeatType: Int,
    val repeatDays: Int, // 位掩码 bit0=周一 … bit6=周日；仅 repeatType=周几 有语义
    val intervalDays: Int, // 间隔天数 N（2..365）；仅 repeatType=间隔 有语义
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

        /** 一次性规则（specs/007）：只在有效期日（仓库写=当天）轮到，跨日未完成进过期分类。 */
        val ONCE = TodoRule(TodoRecurrence.REPEAT_ONCE, 0, 0)
    }
}
