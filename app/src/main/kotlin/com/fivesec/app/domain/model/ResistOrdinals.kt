package com.fivesec.app.domain.model

/**
 * 抵制双序号（specs/011 今日 + specs/016 累计）：覆盖层成功态的同步取号结果。
 * [today] 含本次（先记账后展示）；[total] 为进程生命周期内累计 CANCELED 总数（含本次，
 * 与日期无关——里程碑是总量维度）。
 */
data class ResistOrdinals(val today: Int, val total: Long)
