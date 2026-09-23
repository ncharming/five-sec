# Contract: 待办页 + 拦截卡片 UI（重复规则）

## 待办页行渲染（TodoItemRow 三态表）

| 状态 | Checkbox | 标题 | 标注 | 可勾 | 其他操作（改名/删除/停用/点看全文） |
|---|---|---|---|---|---|
| 启用且轮到 | 可用 | 正常 / 完成划线 | 无 | ✓ | ✓ |
| 启用但不轮到 | **禁用** | alpha 弱化 | 「今天不用做」（labelSmall，标题下方） | ✗ | ✓ |
| 停用 | 禁用 | alpha 0.62（既有） | 不显示「今天不用做」（不可勾原因由开关表达） | ✗ | ✓ |

- 「今天不用做」标注条件：`isEnabled && !dueToday`——停用与不轮到叠加时不显示标注（避免双重误导）。
- 点击条目行 → 只读全文弹窗：**任何状态都可用**（不轮到也允许看全文）。

## TodoRow 扩展（TodoViewModel）

```kotlin
data class TodoRow(val todo: Todo, val doneToday: Boolean, val dueToday: Boolean)
```

- `dueToday` = `TodoRecurrence.isDue(todo.repeatType, ..., lastCompletedDate, today)`，在 `rows` 的 combine 派生中计算（today MutableStateFlow 参与 combine → `refreshToday()` 跨日自动重算灰显态）。
- `setCompleted`：仅对 `dueToday` 行可用（UI 禁用兜底 + VM 不额外拦截——与 005 停用行同模式）。

## 编辑弹窗规则区（TodoEditDialog 扩展，新建/重命名共用）

- 「重复」段：三选一 SegmentedButton——每天 / 按星期几 / 每 N 天。
- 选「按星期几」：展开 7×FilterChip（周一…周日，ISO 周一首位，多选）；**全不选时保存按钮禁用 + 行内提示「至少选择一天」**（FR-002 主拦截）。
- 选「每 N 天」：展开数字输入（OutlinedTextField number），越界 `coerceIn(2..365)` 收敛显示；辅助文案说明"完成后隔 N 天再次出现"。
- 切换规则类型时弹窗内存态保留各规则的上次勾选（体验细节），但**保存时只落当前选中规则**，其余两列归零（见 data-model 不变式）。
- 编辑保存路径：`rename(id, text)` + 规则变化时 `setRecurrence(id, rule)`（两个定向 UPDATE 并行）；新建路径：`add(text, rule)`。
- 字数计数/空白校验/200 字截断（005 修订口径）不变。

## 拦截覆盖层卡片（BlockingOverlay 零改动说明）

- `todayTodos(today)` 已过滤为「轮到且启用」→ 卡片 D/T 分母、○ 未完成条目（≤3 行 + 折叠）、全完成 ✓、空块隐藏**自动**只看轮到条目。
- 不轮到的条目：不出现在卡片、不计分母、不计分子（FR-006/FR-007）。
- 混合清单示例：启用 5 条、当日轮到 2 条 → 「今日待办 x/2」；当日无任何轮到条目 → 整块隐藏（与"启用数为 0"同待遇）。

## strings.xml 新增清单

| 键 | 值（占位，落地可微调） |
|---|---|
| `todos_rule_title` | 重复 |
| `todos_rule_daily` | 每天 |
| `todos_rule_weekly` | 按星期几 |
| `todos_rule_interval` | 每 N 天 |
| `todos_rule_dow_1..7` | 周一 … 周日 |
| `todos_rule_interval_days` | 天 |
| `todos_rule_interval_hint` | 完成后隔 N 天再次出现 |
| `todos_rule_weekly_required` | 至少选择一天 |
| `todos_not_due_today` | 今天不用做 |
