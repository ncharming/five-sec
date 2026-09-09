# Contract: 统计范围查询层（DateUtil / DAO / Repository）

**Date**: 2026-09-09 | **Consumers**: `StatsViewModel`、DAO/仓库测试

## DateUtil — 周期起点

```kotlin
object DateUtil {
    fun startOfDayMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long    // 既有
    fun startOfWeekMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long   // 新增：本周一 00:00
    fun startOfMonthMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long  // 新增：本月 1 号 00:00
    fun startOfYearMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long   // 新增：本年 1/1 00:00
}
```

**约定**：
- 输入 `now` 为 epoch 毫秒；输出为该周期起点的 epoch 毫秒；均按传入 `zone`（默认设备本地时区）计算。
- ISO 周：周一为一周第一天；周日输入返回**当期**周一（不含上周数据）。
- 纯函数，无副作用，可独立单测。

## StatsRange — 档位枚举与周期窗口

```kotlin
enum class StatsRange { DAY, WEEK, MONTH, YEAR }

data class StatsPeriod(
    val range: StatsRange,
    val startMillis: Long,
    val endMillis: Long,          // 排他上界
    val month: Int? = null,
    val year: Int? = null,
    val isCurrent: Boolean = false,
)

fun StatsRange.currentPeriod(now: Long, zone: ZoneId = ZoneId.systemDefault()): StatsPeriod
fun StatsRange.availablePeriods(
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    earliestEventMillis: Long? = null,
): List<StatsPeriod>
```

**筛选口径**：日=今日；周=本周/上周；月=当年 1 月至当前月；年=最早事件年份至当前年。列表从最新到最早排序，且不得包含未来周期。月末生成短月选项前必须先落到 1 号。

## DAO — 范围聚合

```kotlin
data class PackageRangeCount(val packageName: String, val total: Int, val opened: Int, val canceled: Int)

@Query("""
    SELECT packageName,
        COUNT(*) AS total,
        SUM(CASE WHEN outcome = 'OPENED' THEN 1 ELSE 0 END) AS opened,
        SUM(CASE WHEN outcome = 'CANCELED' THEN 1 ELSE 0 END) AS canceled
    FROM interception_events
    WHERE timestamp >= :rangeStart
      AND timestamp < :rangeEnd
    GROUP BY packageName
""")
fun observeCountsByPackageBetween(rangeStart: Long, rangeEnd: Long): Flow<List<PackageRangeCount>>

@Query("SELECT MIN(timestamp) FROM interception_events")
fun observeEarliestTimestamp(): Flow<Long?>
```

**约定**：
- `rangeStart` 为 epoch 毫秒，闭区间起点（`>=`）；`rangeEnd` 为排他上界（`<`）。
- `total` 含 INTERRUPTED；`opened + canceled <= total`。
- 无匹配行返回空 List（非 null）；Room Flow 在表变更时自动重发。
- 原 `observeTodayCountsByPackage` / `PackageTodayCount` 由本契约**取代**（删除旧签名）。

## Repository — 透传

```kotlin
fun observeCountsByPackageBetween(rangeStart: Long, rangeEnd: Long): Flow<List<PackageRangeCount>>
fun observeEarliestTimestamp(): Flow<Long?>
```

**不变式**：仓库层不计算周期、不缓存结果；周期窗口计算只发生在 ViewModel（档位/周期选择时）。

## 移除项（去冗余契约）

- `AppStatistics` 实体、`AppStatisticsDao`、`InterceptionRepository.updateAppStatistics()/getAppStatistics()/observeAllAppStatistics()`
- `record()` 语义收窄为**仅** `eventDao.insert(event)`
- `MIGRATION_2_3`：`DROP TABLE IF EXISTS app_statistics`
