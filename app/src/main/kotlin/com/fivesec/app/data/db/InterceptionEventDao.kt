package com.fivesec.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import kotlinx.coroutines.flow.Flow

@Dao
interface InterceptionEventDao {
    @Insert
    suspend fun insert(event: InterceptionEvent): Long

    @Query("SELECT COUNT(*) FROM interception_events WHERE timestamp >= :startOfDay")
    fun observeTodayCount(startOfDay: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM interception_events WHERE timestamp >= :startOfDay AND outcome = :outcome")
    fun observeTodayCountByOutcome(startOfDay: Long, outcome: InterceptionOutcome): Flow<Int>

    /** 已完成锻炼的日期（按本地日，YYYY-MM-DD），按日期降序。用于计算连击。 */
    @Query(
        "SELECT DISTINCT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS d " +
            "FROM interception_events WHERE exerciseCompleted = 1 ORDER BY d DESC"
    )
    fun observeActiveDays(): Flow<List<String>>
}
