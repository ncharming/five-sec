# Data Model: 重复待办（specs/006）

## Todo（扩展，Room v6）

`todos` 表在 005 四列之上新增 3 列（Room `@Entity` 同步扩展）：

| 字段 | 类型 | 约束/默认 | 说明 |
|---|---|---|---|
| `id` | Long | PK autoGenerate | 不变（创建顺序） |
| `text` | String | ≤200 字、trim 非空白 | 不变（005 修订口径） |
| `isEnabled` | Boolean | 默认 true | 不变（停用条目不进卡片/分母） |
| `lastCompletedDate` | String "" | "yyyy-MM-dd" | 不变；**兼作间隔规则的起算锚点**（见 research R2） |
| `repeatType` | Int | NOT NULL DEFAULT 0 | 0=每天 / 1=按星期几 / 2=每 N 天 |
| `repeatDays` | Int | NOT NULL DEFAULT 0 | 周几位掩码：bit0=周一 … bit6=周日；有效 1..127；仅 type=1 有语义 |
| `intervalDays` | Int | NOT NULL DEFAULT 0 | 间隔天数 N；有效 2..365；仅 type=2 有语义 |

**默认三列全 0 = 每天**：与既有行为逐位等价，升级零感知。

### 不变式（写入侧保证，读侧防御）

- `repeatType = 1` ⇒ `repeatDays ∈ 1..127`（至少一位，UI 与 Repository 双层校验）
- `repeatType = 2` ⇒ `intervalDays ∈ 2..365`（越界由 Repository `coerceIn` 收敛）
- `repeatType = 0` ⇒ `repeatDays = 0`、`intervalDays = 0`（切换回每天时落库归零，避免脏值；编辑弹窗内存态保留用户切走的勾选）
- `lastCompletedDate` 语义不变：勾选写今天、取消清空；任何规则变更**不得**触碰该列（FR-008/FR-009）

### 完成与轮到的关系

- 完成：`lastCompletedDate == today`（惰性求值，不变）
- 轮到：`TodoRecurrence.isDue(repeatType, repeatDays, intervalDays, lastCompletedDate, today)`（新纯函数，判定表见 [contracts/todo-recurrence.md](contracts/todo-recurrence.md)）
- 计入进度/卡片：`isEnabled && 轮到`；可勾选：`isEnabled && 轮到`（灰显行不可勾）

## TodayTodo（不变）

`text + isDone` 两字段保持原样。"轮到"语义在 `TodoRepository.todayTodos(today)` 快照映射过滤时兑现（`isEnabled && isDue`），覆盖层与服务零改动。

## 迁移 v5 → v6（MIGRATION_5_6）

```sql
ALTER TABLE todos ADD COLUMN repeatType INTEGER NOT NULL DEFAULT 0;
ALTER TABLE todos ADD COLUMN repeatDays INTEGER NOT NULL DEFAULT 0;
ALTER TABLE todos ADD COLUMN intervalDays INTEGER NOT NULL DEFAULT 0;
```

- 列定义与 Room 生成的 schema 逐字一致（`exportSchema = false`，回归全靠手建库测试）
- 零数据迁移语句（无 UPDATE/DELETE）：存量行靠列默认值自动归入"每天"
- `interception_events` / `target_apps` / `hints` 零触碰；禁止 `fallbackToDestructiveMigration`

### 迁移效果对照

| v5 行状态 | v6 打开后 | 行为 |
|---|---|---|
| 启用、今天已勾选 | repeatType=0，其余列 0 | 显示已完成，明天自动回未完成（与升级前一致） |
| 启用、未完成 | 同上 | 正常轮到可勾 |
| 停用 | 同上 | 停用弱化、不进卡片/分母（与升级前一致） |

## 轮到判定速查（完整判定表见 contracts/todo-recurrence.md）

| 规则 | 轮到条件 |
|---|---|
| 每天（type=0） | 恒真 |
| 按星期几（type=1） | today 的 DayOfWeek 对应位在 repeatDays 中置位 |
| 每 N 天（type=2） | lastCompletedDate 为空 ⇒ 真；否则 `daysBetween(last, today) >= N` |
