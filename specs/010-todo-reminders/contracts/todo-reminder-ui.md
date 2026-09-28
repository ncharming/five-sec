# Contract: 提醒 UI（specs/010）

## 待办页（TodoScreen）新增三件套

### 1. 新建/编辑弹窗提醒区（TodoEditDialog 扩展）

- 位置：重复规则区之下，同构小节标题「提醒」。
- 形态：一行两态——
  - **未设置**：文本「未设置（点击设置提醒）」，点击弹 Material3 `TimePicker`（`rememberTimePickerState(is24Hour = true)`，已设值回填初始时分）；
  - **已设置**：文本「18:30」+「清除」TextButton（关闭提醒回到未设置态）。
- 确认回调扩展为 `onConfirm(text, rule, reminderTime)`：与文本/规则同弹窗一次性提交；编辑时「没改就不发 UPDATE」的既有口径同样适用（`reminderTime != todo.reminderTime` 才调 `setReminderTime`）。
- 单次规则下提醒区提示语：提醒只在有效期日当天响（复用 dueDate 既有文案体系，见 strings）。

### 2. 行内角标（今日区 + 过期区都显示）

- 副行日期串后追加 ` · 提醒 HH:mm`（`todos_reminder_inline` 资源），仅 reminderTime 非空时。
- 过期区条目照常显示角标（它不会再响——过期单次不排程；角标是「配置可见性」不是「会响承诺」，与决策 12 一致）。

### 3. 权限横幅（页面顶部、PageHeader 之下）

- 出现条件：`todayRows+expiredRows 任一条 reminderTime 非空` && 对应权限缺失。三项各自独立一行（可能同时多条）：
  - 13+ 通知权限缺 → 「未开启通知权限，到点提醒将无法显示」+ 按钮：直接弹运行时请求；
  - 12+ 精确闹钟缺 → 「未开启精确闹钟，提醒时间可能不准」+ 按钮：跳 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`；
  - 14+ 全屏显示缺 → 「未开启全屏显示，提醒将只在通知栏横幅显示」+ 按钮：跳 `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`。
- 状态源：Compose 状态 + ON_RESUME 重查（复用既有 lifecycle observer 模式，无轮询）。
- 按钮只负责拉起请求/设置页，不跟踪结果——回来时 ON_RESUME 重查自会消化。

### 4. 首次保存的权限请求（决策 16）

- 确认保存时若 `reminderTime` 非空且（本次从无到有）且 13+ 未授权 → `rememberLauncherForActivityResult(RequestPermission)` 发起请求；结果无论与否保存照常（拒绝不阻断）。

## 全屏提醒页（TodoReminderActivity + TodoReminderViewModel）

- ViewModel（`@HiltViewModel`）：`StateFlow<ReminderUiState>`——`rows: List<ReminderRow(id, text)>`（初始= intent ids 装载并防御过滤；每勾一条即从列表移除）；`complete(id)` 转发仓库 `setCompleted(id, today, true)`；`pendingIds()` 供超时通知带参。
- 界面：竖向居中单卡——标题「到点提醒」+ 提醒时刻、条目列表（Checkbox + 文本，多行省略换行允许）、底部「关闭」按钮。全勾完列表空 → 自动关闭（VM 侧 collect 驱动）。
- 窗口语义：锁屏之上显示、自动亮屏、常亮；不进最近任务；返回手势=关闭（等同点关闭）。
- 60 秒计时：Activity 生命周期内 `lifecycleScope.delay(60_000)`；先到先赢——用户交互（勾/关）取消计时并走正常停铃。

## 文案清单（strings.xml，中文第一语言）

新建/编辑弹窗：`todos_reminder_section`（提醒）、`todos_reminder_none`（未设置 · 点击设置）、`todos_reminder_clear`（清除）、`todos_reminder_once_hint`（提醒只在有效期日当天响）。
行内：`todos_reminder_inline`（提醒 %1$s）。
横幅：`todo_reminder_banner_notifications`、`todo_reminder_banner_exact`、`todo_reminder_banner_fsi`、`todo_reminder_banner_action`（去开启）。
通知/全屏页：`todo_reminder_notification_title`（待办提醒）、`todo_reminder_notification_more`（等 %1$d 条）、`todo_reminder_notice_text`（你有到点的待办未处理）、`todo_reminder_screen_title`（到点提醒）、`todo_reminder_close`（关闭）。

## 不动清单（FR-008 的 UI 面）

- 拦截覆盖层（BlockingOverlay）整卡交互、今日/过期两区骨架、灰显/停用机制、名额体系、详情弹窗、四选一规则编辑器结构——一律零改动（弹窗只**追加**提醒区小节）。
