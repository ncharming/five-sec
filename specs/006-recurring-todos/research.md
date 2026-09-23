# Research: 重复待办技术决策（specs/006）

Phase 0 产出。spec 的全部行为口径已在需求会话中由用户逐项拍板（三选一规则、滚动节奏、灰显语义、卡片只显示轮到、切换不清锚点），无 NEEDS CLARIFICATION；本文件记录**实现层**的决策与权衡。

## R1 规则字段怎么存

**Decision**: `todos` 新增 3 个整型列，默认全 0 = 每天：

| 列 | 含义 | 有效域 |
|---|---|---|
| `repeatType` | 规则类型 | 0=每天 / 1=按星期几 / 2=每 N 天 |
| `repeatDays` | 周几位掩码 | bit0=周一 … bit6=周日（= `DayOfWeek.value - 1`，ISO 周一起点，与统计页周口径一致）；有效值 1..127 |
| `intervalDays` | 间隔天数 N | 2..365；仅 `repeatType=2` 有意义 |

**Rationale**: 三列各管一个维度，读写都是 O(1) 整数比较，定向 UPDATE 与校验直观；位掩码让"周几多选"一列搞定，且与 `java.time.DayOfWeek` 天然互转。

**Alternatives considered**:
- 单列字符串规则（如 `"W1,3,5"` / `"D3"`）——存取省列但每读必解析、校验分散、可读性差，否。
- 独立"规则表"外键关联——上限 20 条的单清单不需要关系化，join 反而破坏快照同步读的简单性，否。
- 组合规则（周几 ∩ 间隔）——spec 明确三选一互斥，不做。

## R2 不新增锚点列（对 spec 实体的实现收敛）

spec 关键实体写了"滚动锚点日（最近完成日为空时=创建日）"。落地时发现**锚点列冗余**：

- 滚动判定两分支：① 从未完成（`lastCompletedDate` 空）⇒ **恒轮到**——锚点根本不参与；② 已完成 ⇒ 起算基准就是 `lastCompletedDate` 本身。
- 两支都不需要创建日 → 不加 `anchorDate` / `createdAt` 列；若未来出现"需要创建日"的需求（如统计）再走正式迁移补列。
- FR-009"切换规则不清锚点"因此自动成立：锚点即最近完成日，规则列的定向 UPDATE 天然不触碰它。

## R3 轮到判定放哪、时区怎么免疫

**Decision**: 新建 `util/TodoRecurrence.kt`，纯 JVM 无 Android import；函数签名只收原始值 + today 字符串：

```kotlin
fun isDue(repeatType: Int, repeatDays: Int, intervalDays: Int, lastCompletedDate: String, today: String): Boolean
```

**Rationale**: 与 `DateUtil` 同层（util 是叶子）；VM（灰显派生）、Repository（快照过滤）、测试三方消费同一实现，口径不会漂移。时区责任在 today 的**产出方**（`DateUtil.todayString(now, zone)`），判定本身字符串进出、无时钟读取 → CI（UTC）免疫，005 已验证的同模式。

**判定细节**：
- 周几：`LocalDate.parse(today).dayOfWeek.value` → bit `(dow-1)` 是否在 `repeatDays` 中置位。
- 间隔：`lastCompletedDate` 空串 → true；否则 `ChronoUnit.DAYS.between(parse(last), parse(today)) >= intervalDays`。完成当天差值 0 < N → 不轮到（立即进灰显期）；第 N 天差值 = N → 重新轮到。
- 时钟回拨（today < last）：差值为负 < N → 不轮到，不崩、不反向清完成状态。
- 防御口径：`last` 非空但解析失败 → 视同从未完成（true，宁可多提醒）；`today` 解析失败 → false（宁可不提醒不崩溃）。正常路径两者都由 `DateUtil` 产出，格式恒合法。

## R4 过滤放在快照映射——覆盖层零改动

**Decision**: `TodoRepository.todayTodos(today)` 的过滤条件从 `isEnabled` 扩为 `isEnabled && isDue`，`TodayTodo` 值类型**保持不变**。

**Rationale**: 覆盖层卡片的全部语义（D/T 分母 = 列表长度、未完成条目列表、"全完成 ✓"、空块隐藏）都建立在 `todayTodos` 返回值上——过滤前置后**服务与 `BlockingOverlay` 一行不改**就获得 FR-006/FR-007。若改为给 `TodayTodo` 加 `isDue` 字段让 overlay 自滤，则多传一个语义字段且两处过滤有口径漂移风险。

**Alternatives considered**: overlay 侧过滤——否（理由如上）；service 侧过滤——否（服务只做事件路由，判定不进服务层）。

## R5 规则编辑的交互与写路径

**Decision**:
- 编辑弹窗（新建/重命名共用）扩展"重复"区：三选一 SegmentedButton + 周几 7×FilterChip（周一首位）+ N 天数字输入（2..365 收敛）。
- 周几全空：**表单层拦截**——保存按钮禁用 + 行内提示「至少选择一天」（FR-002 的用户可见机制）；repo 层 `add`/`setRecurrence` 同口径兜底校验（防御未来新调用方），失败走既有 `Result` + 中文文案模式。
- 写路径：新建 `add(text, rule)`（rule 默认每天 → 既有调用零改动）；编辑确认 = `rename(id, text)` + 规则变化时 `setRecurrence(id, rule)`，两个**定向 UPDATE**——延续 005"不覆盖并发勾选"的模式。非当前规则的其余两列写 0（弹窗内存态会保留用户切走的勾选，落库归零，避免脏值）。

**Alternatives considered**: 合并为一条全量 UPDATE——会覆盖并发勾选/启停，违反 005 契约，否。

## R6 迁移与回归策略

- `MIGRATION_5_6` = 3× `ALTER TABLE todos ADD COLUMN <col> INTEGER NOT NULL DEFAULT 0`（列名/类型/默认值逐字对齐 Room 生成的 schema）；`AppDatabase` version 5→6；`AppModule` 注册。
- `AppDatabaseMigrationTest` 手建 v5 库（旧四列 schema + 含启用/停用/已勾选行）→ 注册全链 `MIGRATION_1_2..5_6` 打开 → 断言行零丢失 + 三列默认 0（= 每天）。
- 老条目 `repeatDays/intervalDays` 为 0 且 `repeatType=0`：判定直接走"每天恒真"，与升级前行为逐位一致。

## R7 文案与展示口径

- 新增资源（中文第一语言）：规则三选一标签（每天/按星期几/每 N 天）、周七短标签（周一…周日）、间隔天数标签与单位（天）、「今天不用做」、「至少选择一天」、间隔输入的辅助文案。
- 灰显行：Checkbox 禁用 + 标题弱化 + 「今天不用做」小字标注；停用行维持既有 0.62 alpha 语义，两态叠加时标注仅由"启用但不轮到"触发（停用行不显示，避免误导——不可勾的原因已经由开关表达）。精确渲染表见 [contracts/todo-ui.md](contracts/todo-ui.md)。
