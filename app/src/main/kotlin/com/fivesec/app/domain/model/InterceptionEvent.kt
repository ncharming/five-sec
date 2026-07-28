package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 一次用户尝试打开目标应用的记录。 */
@Entity(tableName = "interception_events")
data class InterceptionEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestamp: Long,
    val exerciseCompleted: Boolean,
    val outcome: InterceptionOutcome,
)

enum class InterceptionOutcome { OPENED, CANCELED, INTERRUPTED }
