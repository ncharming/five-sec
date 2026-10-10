# Data Model: 使用时长守护（二次减速带）

**Feature Branch**: `012-usage-session-guard`

**Created**: 2026-10-10

**Status**: Draft

## 1. 存储结构

### 1.1 `target_apps`（既有表，加列）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| packageName | TEXT | PK | 不变 |
| appName | TEXT | NOT NULL | 不变 |
| isEnabled | INTEGER | NOT NULL | 不变（拦截开关） |
| isDefault | INTEGER | NOT NULL | 不变 |
| addedAt | INTEGER | NOT NULL | 不变 |
| **sessionGuardEnabled** | INTEGER | NOT NULL DEFAULT 1 | **新增**：该应用会话守护开关（0=关，⋯菜单切换） |

### 1.2 `usage_sessions`（新表，append-only 单行 INSERT）

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | INTEGER | PK AUTOINCREMENT | |
| packageName | TEXT | NOT NULL | 会话应用 |
| startedAt | INTEGER | NOT NULL | 拦截页选「打开」时刻 |
| endedAt | INTEGER | NOT NULL | 结束时刻（语义随 endReason，见 2.2） |
| durationMillis | INTEGER | NOT NULL | endedAt − startedAt，≥0（墙钟口径，含中途短暂离开） |
| guardShownCount | INTEGER | NOT NULL | 本场回弹层实际弹出次数（0–3） |
| endReason | TEXT | NOT NULL | 枚举名，TypeConverter 双向映射 |

索引：无（聚合走全表 GROUP BY，量级=每日数场，远低于 interception_events；不为统计加索引）。

### 1.3 DataStore（既有 `five_sec_settings`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| session_guard_minutes | Int | 15 | 全局回弹间隔，读写均夹取 [5, 60]；预设 5/10/15/20/30/45/60 |

`AppSettings` 增 `sessionGuardMinutes: Int = 15`（domain 纯模型，随 settings 流暴露）。

### 1.4 Room 版本

v11 → **v12**；`MIGRATION_11_12`：`ALTER TABLE target_apps ADD COLUMN sessionGuardEnabled INTEGER NOT NULL DEFAULT 1` + `CREATE TABLE IF NOT EXISTS usage_sessions (...)`。无重命名/无删除/不触碰其余五表。AppModule 迁移链 1→12 全量注册。

## 2. 领域行为

### 2.1 会话生命周期（`UsageSessionTracker` 纯状态机）

```
(无会话) ──onOpened(pkg)──▶ (会话: pkg/startedAt/lastForegroundAt/guardShownCount=0/guarding=真)
   │                              │
   │  前台==pkg: 刷 lastForegroundAt；计时未装且守护中 → ArmTimer(N)（切回重计整段）
   │  前台!=pkg: CancelTimer；now-lastForegroundAt≥30min → CloseSession(IDLE_TIMEOUT, endedAt=lastForegroundAt)
   │  onTimerFired(前台==pkg且guarding): guardShownCount++（达3→guarding=假）
   │                                       → 弹回弹层（标题分钟=自startedAt向上取整）
   │  onGuardContinue: 守护中 → ArmTimer(N)；否则静默
   │  onGuardEnded:    CloseSession(GUARD_ENDED, endedAt=now) → (无会话)
   │  onOpened(新会话): 旧会话先 CloseSession(SUPERSEDED, endedAt=lastForegroundAt)
   │  onServiceStopped: CloseSession(SERVICE_STOPPED, endedAt=lastForegroundAt)
```

### 2.2 结束原因枚举（`UsageSessionEndReason`）

| 值 | 触发 | endedAt |
|---|---|---|
| GUARD_ENDED | 回弹层选「结束」 | now |
| SUPERSEDED | 新会话开启（重开或换目标） | 旧会话 lastForegroundAt |
| IDLE_TIMEOUT | 离开 ≥30 分钟（惰性判定） | lastForegroundAt |
| SERVICE_STOPPED | 服务 onUnbind/onDestroy 兜底 | lastForegroundAt |

### 2.3 与既有拦截链路的接缝

- `userOpenedPkg`/`suppressedPkg`/去抖语义零改动；服务在 appSwitched 块**末尾**追加 `onForegroundSwitched` 路由（位于一切放行早退之前）。
- OPENED 分支在既有动作（标记+抑制+撤层）后追加 `onOpened` 路由。
- `guardOverlay != null` 并入「忽略新弹层」条件；回弹「结束」清 userOpenedPkg/suppressedPkg（与切目标清标记同一写法）+ HOME→250ms 撤层（时序契约沿用）。
- `interception_events` 零触碰：回弹不写拦截事件（无减速带发生）。

### 2.4 统计聚合

`UsageSessionDao.observeDurationByPackageBetween(start, end)`：`SELECT packageName, SUM(durationMillis) AS totalMillis, COUNT(*) AS sessionCount FROM usage_sessions WHERE endedAt >= :start AND endedAt < :end GROUP BY packageName`（半开区间、按结束时刻归期）。UI：分钟 = ⌈totalMillis/60000⌉，>0 才显示「停留 N 分钟」。

## 3. 数据保留与红线

- `usage_sessions` 只增不删，随 90 天统计留存策略同生命周期（当前无清理任务，与事件表一致——留存红线只约束不删，不要求即时清理）。
- 迁移测试断言：存量 target_apps 行 sessionGuardEnabled=1、新表可写可查、四张既有表数据零丢失。
