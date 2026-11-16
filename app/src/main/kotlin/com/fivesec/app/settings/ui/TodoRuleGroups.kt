package com.fivesec.app.settings.ui

import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.settings.viewmodels.TodoRow
import com.fivesec.app.util.TodoRecurrence

/**
 * 今日待办按重复规则分组的纯投影（零 Android import）：编辑弹窗四选一规则的「读侧分类」。
 *
 * 分组键 = 规则**原值**（repeatType + repeatDays + intervalDays + 单次的 dueDate，020）——不同星期
 * 集合、不同 N、不同有效期日各自成组，组名天然自描述（「每周一、三」≠「每周二」是两条节奏，
 * 「单次/2026-11-23」≠「单次/2026-11-30」是两个日子，不是同一类）。重复类的 dueDate 恒空串
 * （仓库写入不变式），分组时归一为空——脏值不拆重复类组。
 * 组序 = **当天轮到的组置顶**（2026-11-16 用户拍板，同日二次修订泛化）→ 其余按编辑弹窗分段顺序
 * （每天→每周→每N天→单次）。置顶档 = 组内任一行「今天轮到」的单次（有效期日=今天）/每N天（今天
 * 复活）/每周几（今天选中）组——每天恒轮到，置顶无意义不参与；未知脏类型不升位（脏值不错位的
 * 同一兜底）。档内序 = 覆盖层 [TodoRecurrence.overlayPriority] 紧急度（单次→每N天→每周）：今日卡
 * 置顶头部与拦截卡片前 3 条同一顺序同一词汇表；未来的单次是计划不是死线，仍留在单次段。
 * 置顶键取 [TodoRow.dueToday]（VM 与灰显「今天不用做」同一次求值）：完成间隔条目即顺延 N 天，
 * 组自然退出置顶——置顶与灰显同源联动，无需再传 today。间隔组按「任一行轮到」整组置顶：
 * 组是节奏的代表，今天有活就先看见。
 * 同类型内：周组按位掩码升序（bit0=周一，周一集合在前）、间隔组按 N 升序、单次组按有效期日
 * 升序（今天在前、未来渐远——近的活先看见）。
 * 组内保持传入序（今日区 = id 升序，由 VM 分区保证）。
 *
 * 防御：未知 repeatType（脏值）不丢行、排在四类正序之后——与 isDue/overlayPriority 的
 * 「脏值不让条目凭空消失」同一思路。过期区（单次 + 2026-10 起重复类错过轮到日）语义是
 * 「失败存量」而非规则分类，不参与分组（specs/007 两卡分区不变）。
 */
object TodoRuleGroups {

    /** 一个展示分组：规则四列即分组键 + 组内行（保持传入顺序）。 */
    data class Group(
        val repeatType: Int,
        val repeatDays: Int,
        val intervalDays: Int,
        val dueDate: String, // 仅单次有语义（020 分组第四维）；重复类恒空串
        val rows: List<TodoRow>,
    )

    /**
     * 按规则分组（当天轮到的组置顶，见类 KDoc）；输入序在组内稳定保留，空输入返回空列表。
     * 置顶判定只读 [TodoRow.dueToday]（VM 已与灰显同源求值），本投影零日期解析、零时钟读取。
     */
    fun groupByRule(rows: List<TodoRow>): List<Group> =
        rows.groupBy { row ->
            with(row.todo) {
                TodoRule(
                    repeatType = repeatType,
                    repeatDays = repeatDays,
                    intervalDays = intervalDays,
                    dueDate = if (repeatType == TodoRecurrence.REPEAT_ONCE) dueDate else "",
                )
            }
        }.map { (key, groupRows) ->
            Group(key.repeatType, key.repeatDays, key.intervalDays, key.dueDate, groupRows)
        }.sortedWith(
            compareBy(
                { if (isDueTodayGroup(it)) 0 else 1 }, // 当天轮到的组置顶（用户拍板，类 KDoc）
                { tierRank(it) },
                { it.repeatDays },
                { it.intervalDays },
                { it.dueDate },
            ),
        )

    /**
     * 置顶组：组内任一行「今天轮到」的单次/每N天/每周几组。每天恒轮到不参与（置顶无意义）；
     * 未知脏类型不升位——与「脏值不让条目凭空消失/错位」同一兜底。间隔组锚点逐行不同
     * （lastCompletedDate 不进分组键），取「任一行轮到」整组置顶：组是节奏的代表，今天有活
     * 就先看见。
     */
    private fun isDueTodayGroup(group: Group): Boolean =
        when (group.repeatType) {
            TodoRecurrence.REPEAT_ONCE, TodoRecurrence.REPEAT_INTERVAL, TodoRecurrence.REPEAT_WEEKLY ->
                group.rows.any { it.dueToday }
            else -> false
        }

    /**
     * 档内序：置顶档=覆盖层 overlayPriority 紧急度（单次→每N天→每周，今日卡与拦截卡片前 3 条
     * 同序）；未置顶档=编辑弹窗四段（每天→每周→每N天→单次）。两档共用后续键（掩码/N/日期）。
     */
    private fun tierRank(group: Group): Int =
        if (isDueTodayGroup(group)) TodoRecurrence.overlayPriority(group.repeatType) else typeRank(group.repeatType)

    /** 组序：与编辑弹窗四段一致；未知类型兜底排最后（不丢行、不打扰四类正序）。 */
    private fun typeRank(repeatType: Int): Int = when (repeatType) {
        TodoRecurrence.REPEAT_DAILY -> 0
        TodoRecurrence.REPEAT_WEEKLY -> 1
        TodoRecurrence.REPEAT_INTERVAL -> 2
        TodoRecurrence.REPEAT_ONCE -> 3
        else -> 4
    }
}
