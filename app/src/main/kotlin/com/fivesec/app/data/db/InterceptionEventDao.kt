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

    /** 在 [rangeStart, rangeEnd) 内按应用聚合：每个包名的拦截总数(total)、打开数(opened)与取消数(canceled)。 */
    @Query(
        "SELECT packageName, " +
            "COUNT(*) AS total, " +
            "SUM(CASE WHEN outcome = 'OPENED' THEN 1 ELSE 0 END) AS opened, " +
            "SUM(CASE WHEN outcome = 'CANCELED' THEN 1 ELSE 0 END) AS canceled " +
            "FROM interception_events " +
            "WHERE timestamp >= :rangeStart AND timestamp < :rangeEnd GROUP BY packageName"
    )
    fun observeCountsByPackageBetween(rangeStart: Long, rangeEnd: Long): Flow<List<PackageRangeCount>>

    /** 已完成锻炼的日期（按本地日，YYYY-MM-DD），按日期降序。用于计算连击。
     *  下界 [since]（yyyy-MM-dd）：连击窗口截断——无界 DISTINCT 会随年限增长每次 INSERT
     *  全量重发（2026-11 优化）；窗口远大于任何现实连击，行为无感知。 */
    @Query(
        "SELECT DISTINCT strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') AS d " +
            "FROM interception_events WHERE exerciseCompleted = 1 " +
            "AND strftime('%Y-%m-%d', timestamp / 1000, 'unixepoch', 'localtime') >= :since " +
            "ORDER BY d DESC"
    )
    fun observeActiveDays(since: String): Flow<List<String>>

    /** 最早拦截事件时间；无事件时为 null。用于生成年份筛选的可选范围。 */
    @Query("SELECT MIN(timestamp) FROM interception_events")
    fun observeEarliestTimestamp(): Flow<Long?>

    /** 一次性统计：[startOfDay, ∞) 内指定结局的事件数（specs/011：今日抵制镜像播种，suspend 非观察）。 */
    @Query("SELECT COUNT(*) FROM interception_events WHERE timestamp >= :startOfDay AND outcome = :outcome")
    suspend fun countByOutcomeSince(startOfDay: Long, outcome: InterceptionOutcome): Int

    /** 全表指定结局计数（specs/016：抵制累计镜像播种——历史是资产，里程碑看总量）。 */
    @Query("SELECT COUNT(*) FROM interception_events WHERE outcome = :outcome")
    suspend fun countByOutcome(outcome: InterceptionOutcome): Int

    /** 周期内全部事件时间戳升序（specs/011 时段分布数据源）：半开区间 [rangeStart, rangeEnd)，
     *  小时分桶在 Kotlin 端按显式 ZoneId 做（SQLite localtime 随运行环境漂移，不可测）。 */
    @Query("SELECT timestamp FROM interception_events WHERE timestamp >= :rangeStart AND timestamp < :rangeEnd ORDER BY timestamp ASC")
    fun observeTimestampsBetween(rangeStart: Long, rangeEnd: Long): Flow<List<Long>>

    /** 全量快照升序（specs/017 导出只读）：一次性 suspend，观察流复用不了——导出要的是时点快照。 */
    @Query("SELECT * FROM interception_events ORDER BY timestamp ASC")
    suspend fun getAllOrdered(): List<InterceptionEvent>
}

/** 周期范围内按应用聚合的查询结果投影（非持久化实体）。 */
data class PackageRangeCount(val packageName: String, val total: Int, val opened: Int, val canceled: Int)
