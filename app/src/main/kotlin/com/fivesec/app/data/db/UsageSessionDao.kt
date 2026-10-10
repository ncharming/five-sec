package com.fivesec.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.fivesec.app.domain.model.UsageSession
import kotlinx.coroutines.flow.Flow

/** 会话事件查询口（specs/012）：只增不删；聚合走实时 GROUP BY（量级=每日数场，无预聚合）。 */
@Dao
interface UsageSessionDao {
    /** 结束时单行写入（append-only 契约的唯一写入点）。 */
    @Insert
    suspend fun insert(session: UsageSession)

    /** 周期内每应用停留时长聚合（半开 [start, end)，按 endedAt 归期）。 */
    @Query(
        "SELECT packageName, SUM(durationMillis) AS totalMillis, COUNT(*) AS sessionCount " +
            "FROM usage_sessions WHERE endedAt >= :start AND endedAt < :end GROUP BY packageName"
    )
    fun observeDurationByPackageBetween(start: Long, end: Long): Flow<List<PackageDurationRow>>
}

/** 聚合行（SUM/COUNT 投影）。 */
data class PackageDurationRow(
    val packageName: String,
    val totalMillis: Long,
    val sessionCount: Int,
)
