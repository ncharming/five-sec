package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 提示语（specs/004-custom-hints）：
 *  kind=stack —— 用户在拦截页保存的一次性提示，LIFO（id 即入栈序，栈顶 = MAX(id)），展示即消费（删除该行）；
 *  kind=pool  —— 用户在管理页维护的自定义提示语池，与内置提示语合并参与随机抽取。
 */
@Entity(tableName = "hints")
data class Hint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val kind: String,
)

object HintKind {
    const val STACK = "stack"
    const val POOL = "pool"
}
