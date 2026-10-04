package com.fivesec.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 待办"轮到/过期"判定纯逻辑（specs/006-recurring-todos；007 增「仅今天」与过期判定）。
 *
 * 为什么在 util 且零时钟读取：与 DateUtil 同层的叶子，被三方消费同一实现——VM（待办页灰显/分区派生）、
 * Repository（覆盖层快照过滤）、单测（判定表）。进出均为 `DateUtil.todayString` 口径的 yyyy-MM-dd
 * 字符串，时区责任在 today 的产出方（TimeProvider + ZoneId），本文件不读系统时钟 → CI（UTC）与
 * 真机行为逐位一致（005 已验证的同模式）。
 *
 * 滚动间隔语义（006 用户拍板口径）：从未完成恒轮到（锚点不参与）；完成后以最近完成日整日起算，
 * 第 N 天复活；到期后持续轮到直到完成——拖延只顺延、不"错过就没"（今天永远是补救日）。
 *
 * 一次性语义（007 用户拍板口径）：只在有效期日（dueDate）当天轮到；跨日未完成 = 过期（进过期分类，
 * 不可补勾）；已完成的一次性不算过期（那是"完成待清理"，由仓库惰性物理删除收尾）。
 *
 * 过期口径（2026-10-04 用户修订，取代 007 的「重复类不进过期分类」）：重复类错过各自最近一个
 * 轮到日同样过期（详见 [lastMissedDueDate]）——过期了就一定显示在过期区；与「拖延只顺延」并存
 * 不矛盾：过期是失败的事实账，顺延是今天的活路（今天轮到仍可勾，勾掉即离开过期区）。
 */
object TodoRecurrence {

    /** 规则类型：0=每天（默认，specs/005 存量即此形态）/ 1=按星期几 / 2=每 N 天 / 3=仅今天（specs/007）。 */
    const val REPEAT_DAILY = 0
    const val REPEAT_WEEKLY = 1
    const val REPEAT_INTERVAL = 2
    const val REPEAT_ONCE = 3

    /** 间隔 N 有效域：N=1 等价"每天"，由每天规则表达不重复提供；上限一年。 */
    const val MIN_INTERVAL_DAYS = 2
    const val MAX_INTERVAL_DAYS = 365

    /** DayOfWeek.value（1=周一 … 7=周日）→ 位掩码位（bit0=周一 … bit6=周日），ISO 周一起点与统计页同口径。 */
    fun bitOf(dayOfWeek: DayOfWeek): Int = 1 shl (dayOfWeek.value - 1)

    /** 周几集合 → 位掩码；空集得 0（非法形态，由调用方校验拦截，判定侧按未命中处理）。 */
    fun weeklyMask(days: Set<DayOfWeek>): Int = days.fold(0) { acc, d -> acc or bitOf(d) }

    /** 间隔天数越界收敛（表单与 Repository 双层共用同一口径）。 */
    fun coerceIntervalDays(days: Int): Int = days.coerceIn(MIN_INTERVAL_DAYS, MAX_INTERVAL_DAYS)

    /** 覆盖层卡片展示优先级（用户拍板口径）：仅今天 → 每N天 → 每周几 → 每天；返回值小者在前。
     *  不直接复用 repeatType 数值倒序——那只是常量巧合，规则显式化后将来加类型不踩隐式依赖。
     *  未知类型兜底与每天同档（脏值不让条目凭空消失，只是排最后，与 isDue 的兜底思路一致）。 */
    fun overlayPriority(repeatType: Int): Int = when (repeatType) {
        REPEAT_ONCE -> 0
        REPEAT_INTERVAL -> 1
        REPEAT_WEEKLY -> 2
        else -> 3
    }

    /**
     * 今天是否轮到（惰性求值、无状态、纯内存）。
     *
     * 防御口径：today 非法 → false（宁可不提醒不崩溃）；lastCompletedDate 非空但非法 → 视同从未完成
     * （宁可多提醒）；仅今天的 dueDate 空/非法 → 不轮到（脏数据兜底，正常路径由仓库写入恒合法）。
     *
     * @param dueDate 一次性有效期日，仅 [REPEAT_ONCE] 有语义（重复类传空串即可）
     */
    fun isDue(
        repeatType: Int,
        repeatDays: Int,
        intervalDays: Int,
        lastCompletedDate: String,
        dueDate: String,
        today: String,
    ): Boolean {
        val todayDate = today.toLocalDateOrNull() ?: return false
        return when (repeatType) {
            REPEAT_WEEKLY -> {
                val mask = 1 shl (todayDate.dayOfWeek.value - 1)
                repeatDays and mask != 0
            }

            REPEAT_INTERVAL -> {
                val last = lastCompletedDate.toLocalDateOrNull()
                    ?: return true // 空/非法 = 从未完成 = 恒到期
                ChronoUnit.DAYS.between(last, todayDate) >= intervalDays.coerceAtLeast(1)
            }

            REPEAT_ONCE -> dueDate.toLocalDateOrNull() == todayDate // 有效期日=今天才轮到

            else -> true // 每天（未知类型兜底为每天，避免脏值让条目从清单里"消失"）
        }
    }

    /**
     * 「每 N 天」规则的下一次轮到日（编辑弹窗「下次执行」展示用，yyyy-MM-dd 口径）。
     *
     * 为什么与 [isDue] 严格同源：展示口径必须与灰显/卡片过滤口径一致，否则弹窗说"今天轮到"
     * 列表却灰显。从未完成（锚点空/非法）恒到期 → 今天；已完成则自锚点日整日起算第 N 天复活，
     * 已到期（拖延顺延）收敛为今天——答的是"下一次轮到"，不是理论锚点日。
     * 防御：today 非法返回 null（调用方隐藏该行，宁可不展示不崩溃）；N 由调用方先收敛
     * （表单/仓库双层 2..365），此处 coerceAtLeast(1) 仅防死值。
     */
    fun nextIntervalDueDate(lastCompletedDate: String, intervalDays: Int, today: String): String? {
        val todayDate = today.toLocalDateOrNull() ?: return null
        val last = lastCompletedDate.toLocalDateOrNull() ?: return todayDate.toString()
        val days = intervalDays.coerceAtLeast(1)
        return if (ChronoUnit.DAYS.between(last, todayDate) >= days) {
            todayDate.toString()
        } else {
            last.plusDays(days.toLong()).toString()
        }
    }

    /**
     * 是否已过期（specs/007；2026-10-04 用户修订口径）：存在「轮到了却没完成」且尚未补救的过去
     * 轮到日（= [lastMissedDueDate] 非空）——**过期了就一定显示在过期区**。
     *
     * 口径演变：007 原拍板「重复类恒不过期（拖延只顺延）」，2026-10-04 废止——每天/每周几/每 N 天
     * 错过各自最近一个轮到日即过期；单次口径不变（有效期日 < 今天且未完成）。已完成的不算过期
     * （单次=完成待清理由仓库惰性删除收尾；重复类=完成日覆盖了错过日即视为已补救）。过期是相对
     * 今天的推导态、不看启用开关（停用不豁免失败，007 口径延续）。防御：today/dueDate 脏值按
     * 未过期兜底（脏数据不让条目凭空消失，暂由今日区兜底展示）。
     */
    fun isExpired(
        repeatType: Int,
        repeatDays: Int,
        intervalDays: Int,
        lastCompletedDate: String,
        dueDate: String,
        createdAt: String,
        today: String,
    ): Boolean = lastMissedDueDate(
        repeatType,
        repeatDays,
        intervalDays,
        lastCompletedDate,
        dueDate,
        createdAt,
        today,
    ) != null

    /**
     * 最近一次错过的轮到日（yyyy-MM-dd；null = 无未补救的错过）——过期判定与过期区副行
     * 「哪天失败的」的统一数据源（单次即有效期日）。
     *
     * 各规则「过去的轮到日」怎么找（与 [isDue] 严格同源，否则过期区说失败、列表说没轮到）：
     *  - 单次：有效期日 dueDate（< 今天才有「过去」可言）。
     *  - 每天（含未知类型兜底）：昨天——须条目昨天已存在（createdAt ≤ 昨天；v7 前老数据创建日
     *    为空串，视同久已存在：昨天的轮到真实发生过，不因缺列抹掉失败）。
     *  - 每周几：过去 7 天内最近的选中周几（同样须 ≥ createdAt）；空集永不轮到 → 永无错过。
     *  - 每 N 天：已完成 → 计划复活日 = 最近完成日 + N（复活日 < 今天 = 该轮错过；到期后持续
     *    轮到的顺延期按计划复活日归因「哪天失败的」）；从未完成 = 恒轮到（isDue 同分支），同每天
     *    按昨天。
     *
     * 收口：候选日须晚于 lastCompletedDate——在候选日当天或之后完成过 = 已补救（含「今天完成
     * 顺带清掉昨天的错过」：重复类今天仍是补救日，这是「拖延只顺延」留给今天的活路）。
     * 防御：today 非法 → null（宁可不警示不崩溃）；lastCompletedDate 非法 → 视同从未完成。
     */
    fun lastMissedDueDate(
        repeatType: Int,
        repeatDays: Int,
        intervalDays: Int,
        lastCompletedDate: String,
        dueDate: String,
        createdAt: String,
        today: String,
    ): String? {
        val todayDate = today.toLocalDateOrNull() ?: return null
        val last = lastCompletedDate.toLocalDateOrNull() // 空/非法 = 从未完成（宁可多警示）
        val candidate: LocalDate? = when (repeatType) {
            REPEAT_ONCE -> {
                if (last != null) return null // 已完成的单次不算过期（完成待清理由仓库惰性删除收尾）
                val due = dueDate.toLocalDateOrNull() ?: return null
                if (due < todayDate) due else null // 当天=今天轮到、未来=时钟回拨防御，都不过期
            }

            REPEAT_WEEKLY -> {
                val created = createdAt.toLocalDateOrNull() ?: LocalDate.MIN
                var found: LocalDate? = null
                for (offset in 1L..7L) { // 过去 7 天恰好每个周几各一次；今天本身不算（那是「今天轮到」）
                    val day = todayDate.minusDays(offset)
                    if (day < created) break // 再往前都在创建之前，不可能轮到过
                    if (repeatDays and (1 shl (day.dayOfWeek.value - 1)) != 0) {
                        found = day
                        break
                    }
                }
                found
            }

            REPEAT_INTERVAL -> if (last != null) {
                val revival = last.plusDays(intervalDays.coerceAtLeast(1).toLong()) // N 收敛与 isDue 同口径
                if (revival < todayDate) revival else null // 复活日当天=今天轮到，不算错过
            } else {
                yesterdayIfExists(createdAt, todayDate) // 从未完成 = 恒轮到（isDue 同分支）
            }

            else -> yesterdayIfExists(createdAt, todayDate) // 每天（未知类型兜底与每天同档）
        }
        return candidate?.takeIf { last == null || it > last }?.toString()
    }

    /** 昨天是否为一条已存在的轮到日（每天/从未完成的间隔共用：条目昨天在册即轮到过）。 */
    private fun yesterdayIfExists(createdAt: String, today: LocalDate): LocalDate? {
        val created = createdAt.toLocalDateOrNull() ?: LocalDate.MIN // 老数据无创建日：视同久已存在
        val yesterday = today.minusDays(1)
        return if (yesterday >= created) yesterday else null
    }

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()
}
