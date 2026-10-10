package com.fivesec.app.util

import java.time.Instant
import java.time.ZoneId

/**
 * 拦截事件的 24 小时时段分布（specs/011 拦截反馈三件套）：把周期内事件时间戳按本地时区
 * 的小时（0–23）分桶，供统计页「时段分布」柱状图回答「几点最容易破防」。
 *
 * 为什么在 Kotlin 分桶而不在 SQL GROUP BY：SQLite 的 strftime('localtime') 时区行为随
 * 运行环境漂移（CI 是 UTC、真机是 CST），无法显式注入；这里按仓库约定显式传 [zone]
 * （与 DateUtil/StatsRange 同口径），纯函数可直接单测。
 * 数据量为个人级事件流水（年级别数千行），内存分桶无压力。
 * 计入全部结局（含 INTERRUPTED）——与「拦截次数」总数同口径，峰值洞察不被打断事件扭曲。
 */
object HourDistribution {

    const val BUCKETS = 24

    /** 各事件的本地小时桶计数；空列表返回全零数组（零是有效数据）。 */
    fun of(timestamps: List<Long>, zone: ZoneId): IntArray {
        val buckets = IntArray(BUCKETS)
        for (ts in timestamps) {
            val hour = Instant.ofEpochMilli(ts).atZone(zone).hour
            buckets[hour]++
        }
        return buckets
    }
}
