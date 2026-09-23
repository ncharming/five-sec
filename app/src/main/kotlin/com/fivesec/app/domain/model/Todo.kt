package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.fivesec.app.util.TodoRecurrence

/**
 * 每日待办（specs/005-daily-todos；006 起带重复规则；007 起带一次性规则与创建日）。
 *
 * "每日重置"是惰性求值而非定时任务：完成判定 = [lastCompletedDate] == 今天（yyyy-MM-dd，
 * `DateUtil.todayString` 口径）——跨入新的一天后旧日期自然失配，无需任何清理逻辑。
 * 完成历史不留在本表（仅记最近完成日），统计页的任务历史经独立的 TodoCompletion
 * 事件表聚合（specs/008-todo-stats：勾选落一行/取消删当日，文本快照防删除失联）。
 *
 * 重复规则三列（006）：`lastCompletedDate` 兼作间隔规则的滚动起算锚点（最近完成日为空 = 恒轮到，
 * 故无需创建日列）；切换规则绝不触碰该列（完成状态是既成事实）。
 *
 * 007 两列：[createdAt]（创建日，只在插入时写、永不改写——行内副行展示，老数据空串显示「—」）
 * 与 [dueDate]（一次性规则「仅今天」的有效期日：==今天轮到、<今天未完成=过期、转回重复类清空；
 * 「改为今天」只重写此列）。两列分离是因为「复活可改写有效期」与「创建时间不可变」是两个不变量。
 */
@Entity(tableName = "todos")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String, // 标题文本（≤200 字符、trim 后非空白，写入前由 Repository 统一校验；展示层：待办页单行省略、拦截卡片截前 12 字）
    val isEnabled: Boolean = true, // 停用条目不进覆盖层快照、不计入进度分母
    val lastCompletedDate: String = "", // 最近完成日；空串 = 从未完成
    val repeatType: Int = TodoRecurrence.REPEAT_DAILY, // 0=每天 / 1=按星期几 / 2=每 N 天 / 3=仅今天（specs/007）
    val repeatDays: Int = 0, // 周几位掩码 bit0=周一 … bit6=周日；仅 repeatType=1 有语义
    val intervalDays: Int = 0, // 间隔天数 N（2..365）；仅 repeatType=2 有语义
    val createdAt: String = "", // 创建日 yyyy-MM-dd；老数据（v7 前）为空串 → 展示「—」（不伪造迁移日）
    val dueDate: String = "", // 一次性有效期日 yyyy-MM-dd；仅 repeatType=3 有语义，其余恒空串
)

/**
 * 覆盖层"今日待办"快照行（纯 Kotlin 值类型，零 Android import——domain.model 层约束）。
 * 由服务侧在覆盖层创建瞬间经 [TodoRepository.todayTodos] 映射产出，之后定格不变。
 */
data class TodayTodo(
    val text: String,
    val isDone: Boolean,
)
