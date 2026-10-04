package com.fivesec.app.domain.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 待办完成事件（specs/008-todo-stats）：每次勾选待办当天落一行，统计页的历史任务数据由此实时聚合。
 *
 * 为什么单独建表而非扩展 todos：todos.lastCompletedDate 是单值覆盖列（每日惰性重置的基石），
 * 容不下"一天一行"的历史；拦截统计的架构先例（interception_events 事件流水）证明这才是本应用
 * 统计的正确形态——写入点记录事实，查询时聚合，无预聚合表。
 *
 * 唯一索引 (todoId, completedDate)：勾选 upsert、取消勾选定向删除——同一天同一条最多一行，
 * "勾-取消-再勾"永不重复计数（该表不在 interception_events 的只增不删红线内，红线只保护拦截事件）。
 *
 * [todoText] 是勾选当时的文本快照：条目事后被删除/改名，历史统计仍按当时的样子展示，不失联。
 *
 * [wasExpired]（2026-10-04 二次修订）：这条完成是不是「过期补完」——从过期区的完成入口勾掉时为
 * true（补的是错过的轮到日），从今日区/提醒页的正常入口勾掉为 false。统计侧据此把「过期补完」
 * 与「正常完成」显式分列，补勾不冒充准时。同日重勾以最后一次入口为准（REPLACE 覆盖）。
 */
@Entity(
    tableName = "todo_completions",
    indices = [Index(value = ["todoId", "completedDate"], unique = true)],
)
data class TodoCompletion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val todoId: Long,
    val todoText: String,
    val completedDate: String, // yyyy-MM-dd（DateUtil.todayString 口径；定长零填充，字典序=时间序）
    val wasExpired: Boolean = false, // 过期补完标记（Room v11 列；存量行迁移回填 false）
)
