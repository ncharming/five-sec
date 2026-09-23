package com.fivesec.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.util.TodoRecurrence
import kotlinx.coroutines.flow.Flow

/**
 * 待办表访问（specs/005-daily-todos；006 规则三列；007 增 dueDate/createdAt 口径）。
 *
 * 变更一律走定向 UPDATE 而非"读-改-写"整实体：勾选完成、启停、重命名可能并发落在同一条上
 * （待办页操作与拦截快照读取并存），定向列更新天然避免互相覆盖。id 升序 = 创建顺序。
 */
@Dao
interface TodoDao {

    @Query("SELECT * FROM todos ORDER BY id ASC")
    fun observeAll(): Flow<List<Todo>>

    /** 按 id 取单条（完成事件写文本快照用）；不存在返回 null。 */
    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun findById(id: Long): Todo?

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
     * 定向更新重复规则（specs/006 三列 + specs/007 dueDate 一列，同一语句保证原子）：
     * 绝不触碰 text/isEnabled/lastCompletedDate/createdAt——规则修改与并发勾选/启停互不覆盖，
     * "不清锚点"与"创建时间不可变"由此免费成立。
     * dueDate 语义：转「仅今天」= 当天（仓库按 TimeProvider 产出）；转回重复类 = 清空。
     */
    @Query(
        "UPDATE todos SET repeatType = :repeatType, repeatDays = :repeatDays, intervalDays = :intervalDays, dueDate = :dueDate " +
            "WHERE id = :id",
    )
    suspend fun updateRecurrence(id: Long, repeatType: Int, repeatDays: Int, intervalDays: Int, dueDate: String)

    /** 过期条目「改为今天」（specs/007）：仅重写一次性有效期日为当天，其余列一概不动。 */
    @Query("UPDATE todos SET dueDate = :date WHERE id = :id")
    suspend fun setDueDate(id: Long, date: String)

    /**
     * 惰性清理（specs/007）：已完成的一次性待办，完成日不是今天 → 物理删除（无后台任务，
     * 由仓库在 observeAll 收集路径顺手调用；DELETE 触发 Room 重发后二次执行无匹配行，自稳定）。
     * 只作用于 todos 表——interception_events「只增不删」红线不受影响。
     */
    @Query(
        "DELETE FROM todos WHERE repeatType = ${TodoRecurrence.REPEAT_ONCE} " +
            "AND lastCompletedDate != '' AND lastCompletedDate != :today",
    )
    suspend fun purgeCompletedOneOffs(today: String)

    @Query("DELETE FROM todos WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM todos")
    suspend fun count(): Int
}
