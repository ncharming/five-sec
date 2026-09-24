# Contract: 拦截覆盖层内容层（提示语退役后）与底部导航

**Date**: 2026-09-24 | **Spec**: [spec.md](../spec.md) | **前置**: [specs/005 contracts/todo-ui.md](../../005-daily-todos/contracts/todo-ui.md) §B 由本契约废止并继任

## A. BlockingOverlay 构造与布局

### 构造参数

```kotlin
class BlockingOverlay(
    context: Context,
    appLabel: String,
    todos: TodayTodosSnapshot,          // hint: String 参数删除（D8）
    onFinished: (InterceptionOutcome) -> Unit,
)
```

### 布局（自上而下）

```text
titleText（是否打开 xxx？，24sp 加粗）
spacer(12)
todoBlock（常驻 VISIBLE——四态渲染见 §B；不再有"空清单整卡 GONE"分支）
spacer(28)
countdownLine（请先思考 N 秒 → ✓ 请选择，22sp 品牌绿；零改动）
spacer(28)
按钮行（取消 | 打开，render() 锁定/解锁零改动）
```

- **删除元素**：`hintText` TextView、`hint` 构造参数、原 hintText 两侧 spacer 节奏位；`spacerBeforeTodos` 由"空清单时 GONE"改为**常驻 VISIBLE**（卡片不再隐藏，§B 空态常驻的布局前提）。
- 卡片仍为构造时**定格**，不参与 `render()` 状态切换（005 契约延续）。
- `styleAsTextAction`、品牌色 token、`INTERRUPTED` 降级路径均零改动。

## B. 待办卡四态渲染规则

| 状态（快照派生，见 data-model §2） | todoTitle | todoItems | 标题色 |
|---|---|---|---|
| `CONTENT`（部分完成） | 今日待办 D/T | `○ 未完成1`…（≤3 行，每条截前 12 字）+（超出时）`…还有 N 条` | onSurface |
| `ALL_DONE` | 今日待办已全部完成 ✓ | GONE | primary（品牌绿） |
| `EMPTY_NONE_ENABLED` | 还没有今日待办 · 打开「五秒」添加 | GONE | onSurfaceVariant |
| `EMPTY_NONE_DUE` | 今天没有轮到的待办 | GONE | onSurfaceVariant |

- 新增文案 `strings.xml`：`blocking_todos_empty_none` / `blocking_todos_empty_none_due`（措辞可调、语义锁定：引导添加 vs 告知不轮到）。
- 空态与全完成态形态一致（仅标题行）；空态**只读**——无输入、无勾选、无跳转（spec Edge Cases）。
- D/T 分母、○ 列表、12 字截断、`overlayOrder` 排序口径全部不变（005/007 契约延续）。

## C. 服务接线（`AppBlockerAccessibilityService`）

- EntryPoint 摘 `hintRepository()` 成员；`blocking_exercise_hints` 资源读取与 `takeNextHint` 调用删除。
- Block 分支：`val todos = todoRepository.todayTodos(DateUtil.todayString(...))`，快照直传 `BlockingOverlay`——主线程同步取数**只剩这一处**（保持快照注入模式，不新增异步等待）。

## D. 导航契约（`HomeScreen`）

```kotlin
private enum class HomeTab(...) {
    TODOS, STATS, SETTINGS,   // TIPS 删除（D6）
}
```

- 底部导航：**待办 / 统计 / 设置** 三项，默认 `TODOS`；图标/配色规则不变。
- 恢复规则：`HomeTab.entries.find { it.name == saved } ?: HomeTab.TODOS`（升级窗口旧存档 `"TIPS"` 兜底，防 `valueOf` 崩溃）。
- `HintListScreen` / `HintListViewModel` 文件删除，任何入口不可达（FR-005）。

## E. 退役面清单（用户不可见性验收对照，FR-001/FR-005）

| 面 | 处置 |
|---|---|
| 覆盖层提示语行（内置+自定义轮换） | 删除（§A） |
| 「提示语」Tab 与管理页（池增删、内置开关） | 删除（§D） |
| 内置文案数组 `blocking_exercise_hints` | 资源删除 |
| `hints_*` 字符串 | 删除；共享键改中性名 `common_dismiss`/`common_char_count`（TodoScreen 4 处调用点改名） |
| `hints` 表 / DataStore 两键 | 物理残留、应用零读写（data-model §1/§4） |
