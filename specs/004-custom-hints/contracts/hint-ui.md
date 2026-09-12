# Contract: 覆盖层输入 UI 与提示语管理页

**Date**: 2026-09-12 | **Consumers**: `BlockingOverlay`、`SettingsScreen`、`HintListScreen`、`HintListViewModel`

## BlockingOverlay — 拦截覆盖层（传统 View）

```kotlin
class BlockingOverlay(
    context: Context,
    appLabel: String,
    hintText: String,                      // 新增：展示文本（服务侧 takeNextHint 的结果）
    onSaveHint: (String) -> Unit,          // 新增：用户点击"保存"且文本非空白时回调（已 trim）
    onFinished: (InterceptionOutcome) -> Unit,
)
```

### 布局（垂直列，自上而下）

```text
是否打开 xxx？                 titleText（既有）
<提示语文本>                   hintText（既有位置；内容改为注入参数）
[EditText(weight1) | 保存]     hintInput + saveBtn（新增一行）
  ↳ 占位提示："写句话留给下次拦截（30字内）"
<已保存反馈/空白提示>           hintFeedback（新增，默认隐藏 GONE）
 5                            countdownText（既有）
请先完成 5 秒                   waitText（既有）
[    取消    |    打开    ]     既有按钮行
```

### 行为约定

- **30 字硬截断**：`EditText.filters = arrayOf(InputFilter.LengthFilter(30))`（字符计数，FR-001）；`maxLength` 不做 XML（动态构造 View）。
- **保存**：点击 → `text.trim()`；空白 → 反馈区显示"先写点什么再保存吧"（FR-010）；非空白 → 回调 `onSaveHint(trimmed)`、清空输入框、反馈区显示"已保存，下次拦截时展示"（FR-002）。
- **反馈复位**：`postDelayed` 约 3s 后隐藏（不新开协程，复用 View.post 语义；覆盖层销毁时随 View 树失效，无泄漏）。
- **不干扰状态机**：输入行不参与 `render()` 的按钮锁定/解锁；倒计时期间可输入（FR-009）；`markInterrupted`/finish 逻辑零变化。
- **草稿丢弃**：未点保存即 打开/取消/中断 → 输入内容不入栈（FR-002），覆盖层移除即丢弃。
- 品牌色沿用既有 token（反馈文字用 `onSurfaceVariant`，保存按钮用默认着色或 `primary` 系）。

## SettingsScreen — 入口

```kotlin
@Composable
fun SettingsScreen(
    onOpenAppList: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenHints: () -> Unit,      // 新增参数
    viewModel: SettingsViewModel = hiltViewModel(),
)
```

**约定**：在"拦截应用清单"（`settings_app_list`）按钮**之后**、"统计"（`settings_stats`）按钮**之前**插入 `TextButton`，文案 `settings_hints_entry`（"自定义提示语"），样式与相邻两项一致（bodyLarge）。

## MainActivity — 路由

```kotlin
object Routes {
    const val HINTS = "hints"     // 新增
}
// SETTINGS composable：SettingsScreen(onOpenHints = { navController.navigate(Routes.HINTS) })
// composable(Routes.HINTS) { HintListScreen(onBack = { navController.popBackStack() }) }
```

## HintListViewModel — 管理页状态

```kotlin
@HiltViewModel
class HintListViewModel @Inject constructor(
    private val hintRepository: HintRepository,
) : ViewModel() {
    val hints: StateFlow<List<Hint>>   // = hintRepository.observePool() 按 id 升序（添加顺序）
    fun add(text: String)              // trim → 空白忽略；>30 字截断 → hintRepository.addPoolHint
    fun remove(id: Long)               // → hintRepository.removePoolHint
}
```

**约定**：`add` 的截断/空白规则与覆盖层一致（同一 `HintRepository.MAX_HINT_LENGTH`）；返回值无（失败静默，UI 输入框已限长，空白由对话框确认按钮禁用/忽略兜底）。

## HintListScreen — 管理页（Compose）

```kotlin
@Composable
fun HintListScreen(onBack: () -> Unit, viewModel: HintListViewModel = hiltViewModel())
```

### 结构

- `TopAppBar`：标题 `hints_title`（"自定义提示语"）+ 返回箭头。
- 列表：`LazyColumn`，每行 = 提示语文本（bodyLarge）+ 删除 `IconButton`（Close 图标，与 AppListScreen 同构）；空态文案 `hints_empty`。
- 添加：TopAppBar `Add` action 或底部按钮 → `AlertDialog`：`OutlinedTextField`（占位 `hints_input_hint`、`LengthFilter(30)`、单行）+ 确认（`hints_add_confirm`，空白时点击无效）/ 取消（`hints_dismiss`）。

### 交互约定

- 添加成功即关闭对话框、列表即时刷新（Flow 驱动，Room 自动重发）。
- 删除即时生效，无确认弹窗（与清单页删应用一致的低摩擦模式）。

## strings.xml — 新增文案

```xml
<!-- Blocking overlay custom hint input -->
<string name="blocking_hint_input_hint">写句话留给下次拦截（30字内）</string>
<string name="blocking_hint_save">保存</string>
<string name="blocking_hint_saved">已保存，下次拦截时展示</string>
<string name="blocking_hint_empty">先写点什么再保存吧</string>

<!-- Settings / hint manager -->
<string name="settings_hints_entry">自定义提示语</string>
<string name="hints_title">自定义提示语</string>
<string name="hints_add">添加提示语</string>
<string name="hints_empty">还没有自定义提示语。添加后会与内置提示随机出现。</string>
<string name="hints_input_hint">输入提示语（不超过30字）</string>
<string name="hints_add_confirm">添加</string>
<string name="hints_dismiss">取消</string>
```
