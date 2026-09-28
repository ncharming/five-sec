# Data Model: 待办到点主动提醒（specs/010）

## 表变更：`todos`（Room v9 → v10）

新增一列（`MIGRATION_9_10`，纯 ADD COLUMN，零数据迁移语句）：

| 列 | 类型 | 默认 | 语义 |
|---|---|---|---|
| `reminderTime` | TEXT NOT NULL | `''` | 提醒时刻 `HH:mm`（24 小时制、分钟精度、秒恒 00）。空串 = 无提醒（默认关）。日期语义完全由既有规则列推导：重复类=每个轮到日该时刻、单次=dueDate 当天该时刻——**不存完整时间点，不存双份日期** |

列定义与 Room 为实体生成的 schema 逐字一致（AppDatabaseMigrationTest 手建 v9 库守卫，模式同 006/007）。存量行回填空串：零排程、零通知，升级零感知。

`AppDatabase` version = 10；AppModule 注册 `MIGRATION_9_10` 入链。

## 时刻格式与校验

- 合法域：`^([01]\d|2[0-3]):[0-5]\d$`（`TodoReminderPlanner.isValidReminderTime`）。
- 空串合法（= 清除提醒）；其余非法输入由 Repository `setReminderTime` 拒绝（中文文案），表单层 TimePicker 天然产出合法值（`%02d:%02d`）。
- 展示层行内角标 `提醒 18:30`（strings.xml 资源，不硬编码）。

## 判定纯逻辑（reminder/TodoReminderPlanner，零 Android import）

```kotlin
isValidReminderTime(time: String): Boolean
// 空串 true（清除）；否则 HH:mm 正则

isDueForMinute(todo: Todo, reminderDay: String, minute: String): Boolean
// todo.isEnabled && todo.reminderTime == minute
// && TodoRecurrence.isDue(todo 规则五参, reminderDay)
// && todo.lastCompletedDate != reminderDay
// —— 响前查库的唯一过滤口径（receiver 用）；reminderDay/minute 取自排程时刻（intent extra），
//    与「当前时间」解耦：非精确降级晚到数分钟仍正确聚合当分钟条目

nextTriggerAt(todo: Todo, nowMillis: Long, zone: ZoneId): Long?
// !isEnabled 或 reminderTime 空/非法 → null
// 单次：dueDate < 今天 → null（过期）；dueDate == 今天 → 今天@HH:mm（已过/已完成 → null）；
//       dueDate > 今天（防御分支，正常不可达）→ 该日@HH:mm
// 重复类：自今天起逐日扫描（视界 370 天 ≥ 365 间隔上限 + 周几余量）：
//   该日不轮到 → 跳过；== 今天且已完成 → 跳过；== 今天且时刻 ≤ now → 跳过（不补响）；
//   其余 → 该日@HH:mm 的 epoch millis（ZoneId 显式传入，CI UTC 可跑）

nextTriggerAt(todos: List<Todo>, nowMillis: Long, zone: ZoneId): Long?
// 全量条目中最早的非空 nextTriggerAt；全空 → null（= 无需排程）
```

与 `TodoRecurrence` 同层约定：进出字符串日期口径（yyyy-MM-dd）、零系统时钟读取、防御优先（脏数据宁可 null 不崩）。

## 排程模型（单一「下一响」闹钟）

不按条目各排一个闹钟（20 条 = 20 个 PendingIntent、聚合难），而是**全局只排一个**：最早到点的那个时刻。触发后反查全量、按 `isDueForMinute` 聚合当分钟条目 → 一次响铃 → 重算下一响。任何 todos 表变更（收集器）与开机/时间变更/进 App 都触发重算。优点：天然实现「同刻聚合」（决策 8）、重排是幂等全覆盖（无脏闹钟残留）、错过即静默（决策 9 顺带成立）。

## 写入点（不变量汇总）

- INSERT（add）：reminderTime 由表单传入（经校验，缺省空串）。
- `updateReminderTime(id, time)`：定向 UPDATE 单列，绝不触碰 text/规则/完成态/启停——与既有定向更新族同一并发安全口径。
- 规则修改/复活/启停/删除：不改 reminderTime 列；排程重排由收集器自动吸收。

## 提醒不留痕（红线重申）

提醒触发/展示/关闭不写任何表：`interception_events` 只增不删（产品红线）、`todo_completions` 只承载勾选完成（既有口径，提醒页勾选复用同一入口）。无「已提醒」状态列、无提醒事件表、无统计接入。
