package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 按应用聚合的统计数据，用于统计页面的应用级别展示 */
@Entity(tableName = "app_statistics")
data class AppStatistics(
    @PrimaryKey val packageName: String,     // 目标应用包名
    val totalInterceptions: Long = 0,        // 该应用被拦截的总次数
    val cancellations: Long = 0,             // 用户选择"取消"的次数
    val cancellationRate: Double = 0.0,     // 取消率（cancellations / totalInterceptions）
    val completedExercises: Long = 0,       // 完成5秒锻炼的次数
    val lastUpdated: Long = 0,              // 最后更新时间戳
)