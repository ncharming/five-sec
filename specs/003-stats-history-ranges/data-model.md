# Data Model: 各应用历史数据（日/周/月/年）+ 统计模块去冗余

**Date**: 2026-09-09 | **Spec**: [spec.md](spec.md)

## 实体与表

### 1. `interception_events`（保留，零变更）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK 自增 | 事件 ID |
| `packageName` | String | 目标应用包名 |
| `timestamp` | Long | 事件发生时间（epoch 毫秒） |
| `exerciseCompleted` | Boolean | 是否完成 5 秒锻炼 |
| `outcome` | String（枚举转换器） | OPENED / CANCELED / INTERRUPTED |

- **唯一事实来源**（FR-008）；只增不删（FR-005）。
- 聚合查询 `WHERE timestamp >= :rangeStart GROUP BY packageName` 为全表过滤；个人级数据量无需索引（若未来事件量级显著增长再评估 `timestamp` 索引，当前 YAGNI）。

### 2. `app_statistics`（删除）

v2 遗留冗余汇总表（`packageName` PK + 累计计数 + 汇总率）。**v3 迁移中 DROP**，实体 `AppStatistics`、DAO `AppStatisticsDao`、双写逻辑同步删除。

### 3. `target_apps`（不动）

应用卡片展示范围仍由清单驱动（FR-006）；清单变更不影响事件表数据。

## 数据库版本

| 版本 | 变更 | 迁移 |
|---|---|---|
| v2（现状） | 含 `app_statistics` | — |
| **v3（本特性）** | 实体列表移除 `AppStatistics` | `MIGRATION_2_3`: `DROP TABLE IF EXISTS app_statistics` |

```kotlin
// AppDatabase.kt
@Database(
    entities = [TargetApp::class, InterceptionEvent::class], // 移除 AppStatistics
    version = 3,
    exportSchema = false,
)
```

**红线**：禁止引入 `fallbackToDestructiveMigration`（FR-005 升级不丢数据）。

## 新增类型（非持久化）

### `StatsRange`（枚举）

```kotlin
enum class StatsRange { DAY, WEEK, MONTH, YEAR }
```

### 周期起点计算（`DateUtil`）

```kotlin
fun startOfWeekMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long   // 本周一 00:00
fun startOfMonthMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long  // 本月 1 号 00:00
fun startOfYearMillis(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long   // 本年 1/1 00:00
```

实现基于 `ZonedDateTime` / `LocalDate`（ISO 周一起始）；`startOfDayMillis` 既有。

### 查询投影（`PackageTodayCount` → 更名 `PackageRangeCount`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `packageName` | String | 分组键 |
| `total` | Int | 周期内拦截总数（含 INTERRUPTED） |
| `opened` | Int | 周期内 OPENED 数 |
| `canceled` | Int | 周期内 CANCELED 数 |

## 数据流

```text
拦截发生 → insert interception_events（唯一写入点）
统计页   → DAO observeCountsByPackageSince(rangeStart)
         ← Flow<List<PackageRangeCount>>（Room 自动失效重发）
rangeStart = DateUtil.<startOfX>Millis(timeProvider.now())，随档位选择重算
```

## 验证规则（来自需求）

- 四档计数 = 对应周期内事件逐条聚合（SC-002，DAO 测试覆盖）。
- 周期边界：周日属当期周；月初/年初/跨年正确（SC-004，DateUtil 测试覆盖）。
- v2 → v3 升级后事件零丢失、`app_statistics` 消失（US2/US3 场景，迁移测试或手测指南覆盖）。
