package com.fivesec.app.settings.ui

import com.fivesec.app.settings.viewmodels.TodoRow
import com.fivesec.app.util.TodoRecurrence

/**
 * 今日待办按重复规则分组的纯投影（零 Android import）：编辑弹窗四选一规则的「读侧分类」。
 *
 * 分组键 = 规则三列**原值**（repeatType + repeatDays + intervalDays）——不同星期集合、
 * 不同 N 各自成组，组名天然自描述（「每周一、三」≠「每周二」是两条节奏，不是同一类）。
 * 组序 = 编辑弹窗分段顺序（每天→每周→每N天→单次）：页内一个词汇表，学会弹窗就会读列表。
 * 同类型内：周组按位掩码升序（bit0=周一，周一集合在前）、间隔组按 N 升序。
 * 组内保持传入序（今日区 = id 升序，由 VM 分区保证）。
 *
 * 防御：未知 repeatType（脏值）不丢行、排在四类正序之后——与 isDue/overlayPriority 的
 * 「脏值不让条目凭空消失」同一思路。过期区（全是单次）语义是「失败存量」而非规则分类，
 * 不参与分组（specs/007 两卡分区不变）。
 */
object TodoRuleGroups {

    /** 一个展示分组：规则三列即分组键 + 组内行（保持传入顺序）。 */
    data class Group(val repeatType: Int, val repeatDays: Int, val intervalDays: Int, val rows: List<TodoRow>)

    /** 按规则分组；输入序在组内稳定保留，空输入返回空列表。 */
    fun groupByRule(rows: List<TodoRow>): List<Group> =
        rows.groupBy { Triple(it.todo.repeatType, it.todo.repeatDays, it.todo.intervalDays) }
            .map { (key, groupRows) -> Group(key.first, key.second, key.third, groupRows) }
            .sortedWith(compareBy({ typeRank(it.repeatType) }, { it.repeatDays }, { it.intervalDays }))

    /** 组序：与编辑弹窗四段一致；未知类型兜底排最后（不丢行、不打扰四类正序）。 */
    private fun typeRank(repeatType: Int): Int = when (repeatType) {
        TodoRecurrence.REPEAT_DAILY -> 0
        TodoRecurrence.REPEAT_WEEKLY -> 1
        TodoRecurrence.REPEAT_INTERVAL -> 2
        TodoRecurrence.REPEAT_ONCE -> 3
        else -> 4
    }
}
