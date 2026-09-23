package com.fivesec.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.fivesec.app.domain.model.Todo
import kotlinx.coroutines.flow.Flow

/**
 * 待办表访问（specs/005-daily-todos）。
 *
 * 变更一律走定向 UPDATE 而非"读-改-写"整实体：勾选完成、启停、重命名可能并发落在同一条上
 * （待办页操作与拦截快照读取并存），定向列更新天然避免互相覆盖。id 升序 = 创建顺序。
 */
@Dao
interface TodoDao {

    @Query("SELECT * FROM todos ORDER BY id ASC")
    fun observeAll(): Flow<List<Todo>>

    @Insert
    suspend fun insert(todo: Todo): Long

    @Query("UPDATE todos SET text = :text WHERE id = :id")
    suspend fun updateText(id: Long, text: String)

    @Query("UPDATE todos SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 勾选写当天日期、取消写空串（"是否完成"是纯字符串比较，见 Todo.lastCompletedDate）。 */
    @Query("UPDATE todos SET lastCompletedDate = :date WHERE id = :id")
    suspend fun setCompletedDate(id: Long, date: String)

    /**
     * 定向更新重复规则三列（specs/006）：绝不触碰 text/isEnabled/lastCompletedDate——
     * 规则修改与并发勾选/启停互不覆盖；"不清锚点"由此免费成立（锚点=lastCompletedDate）。
     */
    @Query(
        "UPDATE todos SET repeatType = :repeatType, repeatDays = :repeatDays, intervalDays = :intervalDays " +
            "WHERE id = :id",
    )
    suspend fun updateRecurrence(id: Long, repeatType: Int, repeatDays: Int, intervalDays: Int)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM todos")
    suspend fun count(): Int
}
