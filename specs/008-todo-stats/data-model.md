# Data Model: 任务完成统计

**Feature Branch**: `008-todo-stats`

## ER 变更（Room v7 → v8）

```
todo_completions                      ← 新表（specs/008）
├── id            INTEGER PK AUTOINCREMENT
├── todoId        INTEGER NOT NULL            → todos.id（逻辑外键，无 FK 约束——条目删除后行保留）
├── todoText      TEXT NOT NULL               → 勾选当时的文本快照（防删除/改名后历史失联）
└── completedDate TEXT NOT NULL               → yyyy-MM-dd（DateUtil.todayString 口径）
    UNIQUE INDEX (todoId, completedDate)      → 当天同条最多一行
```

todos / interception_events / target_apps / hints 四表零变更。

## 表语义

- **写入**：仅 `TodoRepository.setCompleted(id, today, completed)` 单点——completed=true 先写 todos.lastCompletedDate 再 upsert 事件行；completed=false 写空串并 DELETE (todoId, today)。无其他写入点（对齐 interception_events 的单写入口纪律）。
- **删除**：仅取消勾选的定向 DELETE。无清理任务、无级联（条目删除后事件行保留，历史照常聚合）。
- **读取**：统计页实时聚合（COUNT / GROUP BY todoId），无预聚合表。

## 查询契约

| 查询 | SQL 语义 | 用途 |
|---|---|---|
| observeCountByTodoBetween(start, end) | SELECT todoId, todoText, COUNT(*) … WHERE completedDate >= :start AND < :end GROUP BY todoId ORDER BY completions DESC, todoText ASC | 二级页按条目卡 |
| observeEarliestDate() | SELECT MIN(completedDate) | 年档位可选范围（与拦截最早事件取更早） |

- 区间半开 `[start, end)`，字符串比较（yyyy-MM-dd 字典序=时间序）。
- 总完成次数 = 条目聚合行 SUM(completions)（VM 派生，无需单独 COUNT 查询）。

## 迁移（MIGRATION_7_8）

```sql
CREATE TABLE IF NOT EXISTS `todo_completions` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `todoId` INTEGER NOT NULL,
  `todoText` TEXT NOT NULL,
  `completedDate` TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS `index_todo_completions_todoId_completedDate`
  ON `todo_completions` (`todoId`, `completedDate`);
```

- 列定义与 Room 为 TodoCompletion 生成的 schema 逐字一致（AppDatabaseMigrationTest 手建 v7 库守住）。
- 不回填：部署前的完成历史不存在，查询得 0（specs/002「0 值是有效数据」）。

## 状态派生（无新持久化）

- **今日任务三数**：`TodoTodayStatsCalculator.compute(rows, today)` 纯函数——
  任务 = rows.count { isEnabled && isDue(规则…, today) }（= 覆盖层 D/T 分母）；
  完成 = 其中 lastCompletedDate == today；
  过期 = rows.count { isExpired(…) }（一次性过期，含停用）。
- **周期选择**：StatsPeriod 复用；年可选范围 = min(拦截最早事件毫秒, dateStringToMillis(完成最早日期))。
