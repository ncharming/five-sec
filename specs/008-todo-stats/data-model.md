# Data Model: 任务完成统计

**Feature Branch**: `008-todo-stats`

## ER 变更（Room v7 → v8；2026-10-04 增列 → v11）

```
todo_completions                      ← 新表（specs/008）
├── id            INTEGER PK AUTOINCREMENT
├── todoId        INTEGER NOT NULL            → todos.id（逻辑外键，无 FK 约束——条目删除后行保留）
├── todoText      TEXT NOT NULL               → 勾选当时的文本快照（防删除/改名后历史失联）
├── completedDate TEXT NOT NULL               → yyyy-MM-dd（DateUtil.todayString 口径）
└── wasExpired    INTEGER NOT NULL DEFAULT 0  → 过期补完标记（v11，MIGRATION_10_11）：过期区补勾入口=1、正常入口=0
    UNIQUE INDEX (todoId, completedDate)      → 当天同条最多一行
```

todos / interception_events / target_apps / hints 四表零变更。

## 表语义

- **写入**：仅 `TodoRepository.setCompleted(id, today, completed, wasExpired)` 单点——completed=true 先写 todos.lastCompletedDate 再 upsert 事件行（wasExpired 由调用方按入口传入：过期区补勾 true、今日区/提醒页 false，同日重勾 REPLACE 以最后入口为准）；completed=false 写空串并 DELETE (todoId, today)（取消不区分标记）。无其他写入点（对齐 interception_events 的单写入口纪律）。
- **删除**：仅取消勾选的定向 DELETE。无清理任务、无级联（条目删除后事件行保留，历史照常聚合）。
- **读取**：统计页实时聚合（COUNT / SUM(wasExpired) / GROUP BY todoId），无预聚合表。

## 查询契约

| 查询 | SQL 语义 | 用途 |
|---|---|---|
| observeCountByTodoBetween(start, end) | SELECT todoId, todoText, COUNT(*) AS completions, SUM(wasExpired) AS lateCompletions … GROUP BY todoId ORDER BY completions DESC, todoText ASC | 二级页按条目卡（含过期补完拆分） |
| observeLateCountBetween(start, end) | SELECT COUNT(*) WHERE wasExpired = 1 AND completedDate ∈ [start, end) | 今日卡「今日补完」 |
| observeEarliestDate() | SELECT MIN(completedDate) | 年档位可选范围（与拦截最早事件取更早） |

- 区间半开 `[start, end)`，字符串比较（yyyy-MM-dd 字典序=时间序）。
- 总完成次数 / 总补完次数 = 条目聚合行 sumOf（VM 派生，无需单独 COUNT 查询）。

## 迁移（MIGRATION_7_8；MIGRATION_10_11）

```sql
CREATE TABLE IF NOT EXISTS `todo_completions` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `todoId` INTEGER NOT NULL,
  `todoText` TEXT NOT NULL,
  `completedDate` TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_todo_completions_todoId_completedDate`
  ON `todo_completions` (`todoId`, `completedDate`);

-- MIGRATION_10_11（v10 → v11，2026-10-04 过期补完标记）：
ALTER TABLE todo_completions ADD COLUMN wasExpired INTEGER NOT NULL DEFAULT 0;
```

- 列定义与 Room 为 TodoCompletion 生成的 schema 逐字一致（AppDatabaseMigrationTest 手建 v7 / v10 库守住）。
- 不回填：部署前的完成历史不存在，查询得 0（specs/002「0 值是有效数据」）；存量事件 wasExpired 回填 0——历史无从考证当时是否过期，视作正常完成不伪造。

## 状态派生（无新持久化）

- **今日任务三数**：`TodoTodayStatsCalculator.compute(rows, today)` 纯函数——
  任务 = rows.count { isEnabled && isDue(规则…, today) }（= 覆盖层 D/T 分母）；
  完成 = 其中 lastCompletedDate == today；
  过期 = rows.count { isExpired(…) }（单次 + 2026-10 修订起重复类错过最近轮到日，含停用；与待办页过期区同口径）；
  今日补完 = observeLateCompletionCountBetween(今天, 明天)（事件表 wasExpired 聚合，非 todos 行派生——
  补勾完成即离开过期区，行集口径看不见它，只有事件流水记得住）。
- **周期选择**：StatsPeriod 复用；年可选范围 = min(拦截最早事件毫秒, dateStringToMillis(完成最早日期))。
