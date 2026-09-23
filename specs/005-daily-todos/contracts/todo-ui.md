# Contract: 待办页与拦截覆盖层待办卡片 UI

**Date**: 2026-09-23 | **Spec**: [spec.md](spec.md)

## A. TodoScreen（「待办」Tab，新首页）

### ViewModel（`TodoViewModel`，@HiltViewModel）

```kotlin
data class TodoRow(val todo: Todo, val doneToday: Boolean)

class TodoViewModel @Inject constructor(
    private val todoRepository: TodoRepository,
    private val timeProvider: TimeProvider,
) : ViewModel() {
    val rows: StateFlow<List<TodoRow>>   // combine(observeAll, today) 派生，Eagerly
    fun refreshToday()                   // ON_RESUME 调用：跨日后重算今日口径
    fun add(text: String)                // trim/空白忽略/30 字截断后转发（VM 侧双保险）
    fun rename(id: Long, text: String)
    fun remove(id: Long)
    fun setEnabled(id: Long, enabled: Boolean)
    fun setCompleted(id: Long, completed: Boolean)  // 以 VM 内 today 调 repository
}
```

- `today` 为私有 `MutableStateFlow`（`DateUtil.todayString(timeProvider.now())` 初始化），`refreshToday()` 重算——设备跨日不重启 app 的场景由 ON_RESUME 兜底。
- 派生流规范：`combine` + `stateIn(Eagerly)`（仓库响应式约定）。

### 页面结构（沿用四页统一视觉）

```text
PageHeader(待办, 副标题) [trailing: + FilledIconButton(44dp)]
名额行: 已建 N 条待办 · CapacitySegments(N, 20) · N/20
空态文案（无待办时）
CardSurface:
  TodoRowItem × n（发丝线分隔）:
    Checkbox(doneToday) | 标题（完成划线、停用弱化） | Switch(启用) | ⋯菜单(重命名/删除)
弹窗（FiveSecDialog 外壳）:
  新建 / 重命名（OutlinedTextField + 字数上限 30）
  删除确认
  名额已满提示（"+"兜底）
```

- 勾选仅对启用条目可用；停用条目 Checkbox 禁用、文案弱化（alpha 0.62，与拦截应用停用行同语言）。
- 空态不预置种子数据；文案引导"拦截时会提醒你"点明整合价值。
- 名额行语义：`N` = 已建条数（含停用），上限 20；与拦截应用名额行同构。

## B. BlockingOverlay 待办卡片（只读）

### 构造参数变更

```kotlin
class BlockingOverlay(
    context: Context,
    appLabel: String,
    hint: String,
    todos: List<TodayTodo>,                       // 新增
    onFinished: (InterceptionOutcome) -> Unit,
)   // onSaveHint 参数删除
```

### 布局（提示语文本与倒计时之间）

```text
titleText（是否打开 xxx？）
spacer(12)
hintText（提示语文本，保留）
spacer(12)
todoBlock（LinearLayout vertical，可整体 GONE）:
    todoTitle: 「今日待办 D/T」| 「今日待办已全部完成 ✓」（全完成时）
    todoItems: 未完成条目 ○ 前缀逐行（≤3 行）+「…还有 N 条」
spacer(24)
countdownText / waitText / 按钮行（零改动）
```

### 渲染规则

| 输入（启用待办） | todoTitle | todoItems | todoBlock |
|---|---|---|---|
| 0 条（未建或全停用） | — | — | **GONE**（含前导 spacer） |
| 全部已完成 | 今日待办已全部完成 ✓ | GONE | VISIBLE |
| 部分完成 D/T | 今日待办 D/T | `○ 未完成1`…（≤3 行）+（超出时）`…还有 N 条` | VISIBLE |

- 卡片在覆盖层创建时由构造参数**定格**，存续期间不变；不参与 `render()` 状态切换（倒计时锁定与它无关）。
- 文案进 `strings.xml`；`○` 前缀与 `✓` 同属符号常量（companion），不入资源。
- 删除的元素：`hintInput`/`hintSaveBtn`/`hintFeedback`/`showFeedback`/`hideFeedback`/`hintRow`/`onSaveHint`/`HINT_MAX_LENGTH`/`FEEDBACK_DURATION_MS`；`styleAsTextAction` 保留（cancelBtn 仍用）。

## C. 服务接线（`AppBlockerAccessibilityService`）

- EntryPoint 新增 `fun todoRepository(): TodoRepository`；
- Block 分支：`val todos = todoRepository.todayTodos(DateUtil.todayString(timeProvider.now()))`，与 `hint` 同步获取后传入 `BlockingOverlay`；
- `onSaveHint` 接线删除。
