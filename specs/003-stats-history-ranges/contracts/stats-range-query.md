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

## StatsRange — 档位枚举与起点映射

```kotlin
enum class StatsRange { DAY, WEEK, MONTH, YEAR }

fun StatsRange.startMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long
// DAY → startOfDayMillis；WEEK → startOfWeekMillis；MONTH → startOfMonthMillis；YEAR → startOfYearMillis
```

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
    GROUP BY packageName
""")
fun observeCountsByPackageSince(rangeStart: Long): Flow<List<PackageRangeCount>>
```

**约定**：
- `rangeStart` 为 epoch 毫秒，闭区间起点（`>=`）。
- `total` 含 INTERRUPTED；`opened + canceled <= total`。
- 无匹配行返回空 List（非 null）；Room Flow 在表变更时自动重发。
- 原 `observeTodayCountsByPackage` / `PackageTodayCount` 由本契约**取代**（删除旧签名）。

## Repository — 透传

```kotlin
fun observeCountsByPackageSince(rangeStart: Long): Flow<List<PackageRangeCount>>
```

**不变式**：仓库层不计算周期、不缓存结果；起点计算只发生在 ViewModel（档位选择时）。

## 移除项（去冗余契约）

- `AppStatistics` 实体、`AppStatisticsDao`、`InterceptionRepository.updateAppStatistics()/getAppStatistics()/observeAllAppStatistics()`
- `record()` 语义收窄为**仅** `eventDao.insert(event)`
- `MIGRATION_2_3`：`DROP TABLE IF EXISTS app_statistics`
