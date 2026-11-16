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
 * 组序 = **当天单次置顶**（2026-11-16 用户拍板）→ 其余按编辑弹窗分段顺序（每天→每周→每N天→
 * 单次）：有效期日=今天的单次是「今天不做就没了」的绝对死线，提到卡首——与覆盖层卡片
 * [TodoRecurrence.overlayPriority]「单次最前」同一紧急度口径，两个读侧不再打架；未来单次是
 * 计划不是死线，仍留在单次段。
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
     * 按规则分组（当天单次组置顶，见类 KDoc）；输入序在组内稳定保留，空输入返回空列表。
     * today = VM 持有的今日口径（yyyy-MM-dd，与灰显/分区派发同源）——置顶判定只做字符串
     * 等值比较，不在此读时钟、不解析日期（跨零点由 ON_RESUME 重算联动）。
     */
    fun groupByRule(rows: List<TodoRow>, today: String): List<Group> =
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
                { if (isDueTodayOnce(it, today)) 0 else 1 }, // 当天单次置顶（用户拍板，类 KDoc）
                { typeRank(it.repeatType) },
                { it.repeatDays },
                { it.intervalDays },
                { it.dueDate },
            ),
        )

    /**
     * 置顶键：有效期日=今天的单次组。防御：today 空/脏（正常路径恒合法，仅防坏调用方）
     * 不置顶——空 dueDate 的脏单次组（007 前遗留形态）不因「空==空」升到卡首。
     */
    private fun isDueTodayOnce(group: Group, today: String): Boolean =
        group.repeatType == TodoRecurrence.REPEAT_ONCE && today.isNotEmpty() && group.dueDate == today

    /** 组序：与编辑弹窗四段一致；未知类型兜底排最后（不丢行、不打扰四类正序）。 */
    private fun typeRank(repeatType: Int): Int = when (repeatType) {
        TodoRecurrence.REPEAT_DAILY -> 0
        TodoRecurrence.REPEAT_WEEKLY -> 1
        TodoRecurrence.REPEAT_INTERVAL -> 2
        TodoRecurrence.REPEAT_ONCE -> 3
        else -> 4
    }
}
