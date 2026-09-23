package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 每日待办（specs/005-daily-todos）：固定每日清单条目，每天自动回到未完成。
 *
 * "每日重置"是惰性求值而非定时任务：完成判定 = [lastCompletedDate] == 今天（yyyy-MM-dd，
 * `DateUtil.todayString` 口径）——跨入新的一天后旧日期自然失配，无需任何清理逻辑。
 * v1 不留存完成历史（仅记最近完成日），待办不进统计页（产品共识）。
 */
@Entity(tableName = "todos")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String, // 标题文本（≤200 字符、trim 后非空白，写入前由 Repository 统一校验；展示层：待办页单行省略、拦截卡片截前 30 字）
    val isEnabled: Boolean = true, // 停用条目不进覆盖层快照、不计入进度分母
    val lastCompletedDate: String = "", // 最近完成日；空串 = 从未完成
)

/**
 * 覆盖层"今日待办"快照行（纯 Kotlin 值类型，零 Android import——domain.model 层约束）。
 * 由服务侧在覆盖层创建瞬间经 [TodoRepository.todayTodos] 映射产出，之后定格不变。
 */
data class TodayTodo(
    val text: String,
    val isDone: Boolean,
)
