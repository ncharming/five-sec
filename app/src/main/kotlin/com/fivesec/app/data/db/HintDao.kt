package com.fivesec.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.fivesec.app.domain.model.Hint
import kotlinx.coroutines.flow.Flow

/** 提示语表访问：按 kind 过滤，id 升序（栈序 = 入库序，栈顶为最后一个元素）。 */
@Dao
interface HintDao {

    @Insert
    suspend fun insert(hint: Hint): Long

    @Query("SELECT * FROM hints WHERE kind = :kind ORDER BY id ASC")
    fun observeByKind(kind: String): Flow<List<Hint>>

    @Query("DELETE FROM hints WHERE id = :id")
    suspend fun deleteById(id: Long)
}
