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

    /** 当日按应用聚合：每个包名的拦截总数(total)、打开数(opened)与取消数(canceled)。 */
    @Query(
        "SELECT packageName, " +
            "COUNT(*) AS total, " +
            "SUM(CASE WHEN outcome = 'OPENED' THEN 1 ELSE 0 END) AS opened, " +
            "SUM(CASE WHEN outcome = 'CANCELED' THEN 1 ELSE 0 END) AS canceled " +
            "FROM interception_events WHERE timestamp >= :startOfDay GROUP BY packageName"
    )
    fun observeTodayCountsByPackage(startOfDay: Long): Flow<List<PackageTodayCount>>

    /** 已完成锻炼的日期（按本地日，YYYY-MM-DD），按日期降序。用于计算连击。 */
    @Query(
        "SELECT DISTINCT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS d " +
            "FROM interception_events WHERE exerciseCompleted = 1 ORDER BY d DESC"
    )
    fun observeActiveDays(): Flow<List<String>>
}

/** 当日按应用聚合的查询结果投影（非持久化实体）。 */
data class PackageTodayCount(val packageName: String, val total: Int, val opened: Int, val canceled: Int)
