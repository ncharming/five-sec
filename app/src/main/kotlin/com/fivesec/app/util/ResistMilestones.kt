package com.fivesec.app.util

/**
 * 抵制里程碑判定（specs/016）：累计抵制次数命中里程碑的谓词。
 *
 * 里程碑序列（用户拍板）：**50 次**（第一个值得说的量级）+ **100 的正整数倍**（100/200/300/…）——
 * 连击（Streak）奖「天天做」，里程碑奖「总量」；纯谓词零文案（文案在 strings.xml）。
 */
object ResistMilestones {

    /** 首个里程碑（次）：50——连击之外的第一个总量锚点。 */
    const val FIRST_MILESTONE = 50L

    /** 后续里程碑步长（次）：此后每 100 次。 */
    const val STEP = 100L

    /** [total] 是否命中里程碑；0 与负数恒否（防御脏值）。 */
    fun isMilestone(total: Long): Boolean =
        total > 0 && (total == FIRST_MILESTONE || total % STEP == 0L)
}
