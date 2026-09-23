package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 提示语（specs/004-custom-hints 引入；specs/005-daily-todos 语义收敛）：
 *  kind=pool —— 自定义提示语池，与内置提示语合成单一序列循环展示。
 *  kind=stack（已退役）—— 004 的拦截页一次性提示；其输入入口随 005 移除，
 *  存量行经 MIGRATION_4_5 全部改挂为 pool（用户文字零丢失），代码层不再产生与读取。
 */
@Entity(tableName = "hints")
data class Hint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val kind: String,
)

object HintKind {
    const val POOL = "pool"
}
