package com.fivesec.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fivesec.app.domain.model.TodoCompletion
import kotlinx.coroutines.flow.Flow

/**
 * 待办完成事件表访问（specs/008-todo-stats）。
 *
 * 写入仅经 TodoRepository.setCompleted 单点（勾选 upsert / 取消删当日行）——对齐
 * interception_events 的单写入口纪律；读取只有统计聚合两条路（区间按条目计数、最早日期），
 * 无预聚合表。区间 [startDate, endDate) 半开，yyyy-MM-dd 字符串比较（字典序=时间序）。
 */
@Dao
interface TodoCompletionDao {

    /** 勾选落一行；REPLACE 兜住"同一天重复勾选"（正常路径取消会先删，这里是幂等保险）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(completion: TodoCompletion)

    /** 取消勾选删当日行——取消只发生在当天（勾选框跨日自动失效），按 (todoId, 当天) 定位足够。 */
    @Query("DELETE FROM todo_completions WHERE todoId = :todoId AND completedDate = :date")
    suspend fun deleteByTodoAndDate(todoId: Long, date: String)

    /** 周期内按条目聚合完成次数（次数降序，同次数按文本排序保证稳定展示）；供任务历史二级页。 */
    @Query(
        "SELECT todoId, todoText, COUNT(*) AS completions FROM todo_completions " +
            "WHERE completedDate >= :startDate AND completedDate < :endDate " +
            "GROUP BY todoId ORDER BY completions DESC, todoText ASC"
    )
    fun observeCountByTodoBetween(startDate: String, endDate: String): Flow<List<TodoRangeCount>>

    /** 最早完成日期；无记录时为 null。用于年档位可选范围（与拦截最早事件取更早）。 */
    @Query("SELECT MIN(completedDate) FROM todo_completions")
    fun observeEarliestDate(): Flow<String?>
}

/** 周期内按待办条目聚合的查询结果投影（非持久化实体；todoText 为事件行内快照，同一 todoId 恒同值或取任一行）。 */
data class TodoRangeCount(val todoId: Long, val todoText: String, val completions: Int)
