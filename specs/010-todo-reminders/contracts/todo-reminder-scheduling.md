# Contract: 提醒排程与触发链路（specs/010）

## 组件图（reminder/ 新包，全部新代码；拦截/覆盖层零改动）

```
todos 表 ──observeAll──▶ TodoReminderCoordinator ──nextTriggerAt──▶ TodoReminderScheduler ──▶ AlarmManager（单一闹钟）
                              ▲                                                            │ 到点
                              │ rescheduleNow()                                             ▼
 BootReceiver(BOOT/TIME/TZ/PKG_REPLACED) ─┐                              TodoReminderReceiver
 FiveSecApp.onCreate（进 App 兜底）───────┘                              │ isDueForMinute 反查（触发分钟）
                                                                         ▼
                                              ReminderAlarmService（响铃前台服务：铃声+震动唯一持有方）
                                                ├─ startForeground(FSI 通知：息屏/锁屏直拉全屏页)
                                                ├─ ReminderRinger（MediaPlayer 循环 + 波形震动）+ 60s 超时收底
                                                └─ 亮屏补拉 TodoReminderActivity（无障碍运行=后台启动豁免）
                                                       │ 逐条「完成」──▶ TodoRepository.setCompleted（既有双写）
                                                       │ 关页/全勾完 ──▶ ACTION_FINISH 撤服务
```

**为什么响铃在前台服务而非全屏页（修复轮拍板）**：full-screen intent 只在息屏/锁屏时拉起
Activity，亮屏解锁时平台规定只出高分横幅、部分 ROM 还默认拒绝 FSI——原实现把响铃绑在
Activity.onCreate，导致「只亮横幅、点了才响」。服务由 Receiver 到点直接启动，与页面拉起
与否完全解耦：息屏/亮屏/FSI 被拒都即时响铃+震动。

## TodoReminderScheduler（AlarmManager 封装）

- `scheduleNext(triggerAtMillis: Long)`：单一 PendingIntent（requestCode 固定、FLAG_IMMUTABLE|UPDATE_CURRENT），intent 携带 `EXTRA_TRIGGER_AT = triggerAtMillis`。
- **`setAlarmClock()`（修复轮三拍板）**：AOSP 最高优先级闹钟（PRIORITY_ALARM_CLOCK），触发时系统**真正退出 Doze**（DeviceIdleController.onAlarmClockSend），且**完全不需要精确闹钟权限**（SCHEDULE/USE_EXACT_ALARM 权限体系不适用）——不存在授权异常静默降级被 Doze 推迟的路径；状态栏常驻「即将闹钟」图标（点开回五秒主页），OEM 省电策略对「用户设定的闹钟」类最宽容。这是市面闹钟应用的通行实现。原 setExactAndAllowWhileIdle/canScheduleExactAlarms 分支已删（真机复现：亮屏准点、息屏不触发——setExact 类只在 Doze 内投递不退出 Doze，且受精确闹钟授权牵制）。
- Manifest 仍声明 `USE_EXACT_ALARM`：仅作 12+ 「精确闹钟触发 → 后台启动前台服务」豁免与 ROM 启发式的兼容面（安装即授、不可撤销），调度不依赖。
- `cancel()`：无条件取消 PendingIntent（幂等）。

## TodoReminderCoordinator（重排唯一入口）

- `@Singleton`；init 收集 `TodoRepository.observeAll()`：每次发射 → `TodoReminderPlanner.nextTriggerAt(todos, timeProvider.now(), ZoneId.systemDefault())` → null 则 cancel、否则 scheduleNext。**任何写路径（改时刻/规则/启停/删除/复活/勾选）经 Room 重发自动重排，无遗漏角落。**
- `suspend rescheduleNow()`：`allTodosOnce()` 从库直读（不信内存快照——开机广播时收集器可能未就绪）后同口径重排；供 BootReceiver / FiveSecApp 启动兜底调用。
- 串行执行（收集协程天然串行；rescheduleNow 亦在同一 scope 排队），无并发排程竞态。

## TodoReminderReceiver（到点触发）

1. 读 `EXTRA_TRIGGER_AT`（防御：无 extra → 仅重排后返回）。
2. `goAsync()` + 注入 scope：按**触发时刻**（非当前时间）换算本地 `reminderDay`（yyyy-MM-dd）与 `minute`（HH:mm）。
3. `allTodosOnce().filter { TodoReminderPlanner.isDueForMinute(it, reminderDay, minute) }` —— 响前查库（决策 13）：排程后到点前发生的勾选/停用/删除全部被最新库态吸收。
4. 空集 → 不响（静默，例如到点前刚勾完成）；非空 → 构建静音 FSI 通知（alarm 渠道 v2）→ 启动 `ReminderAlarmService`（前台服务即时响铃+震动；FGS 通知即该 FSI 通知，息屏/锁屏直拉全屏页，亮屏出高分横幅）。启动被拒（12+ 无精确闹钟授权等后台 FGS 限制）→ 退化直发「一次性响铃通知」（builder 级铃声+震动兜底一声一震）。**不再因无通知权限静默跳过**（修复轮拍板：响铃优先——服务响铃 + 亮屏补拉页面都不依赖通知可见性；横幅持续教育授权）。
5. 最后 `rescheduleNow()` 排下一响（链式永续）。

## BootReceiver

`BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` / `MY_PACKAGE_REPLACED` 四动作（均系统保护广播，exported=false）：`goAsync()` → `rescheduleNow()`。覆盖：重启、用户改时钟/时区、应用覆盖更新三种闹钟丢失场景；强停（force-stop）不可恢复是系统机制（README 平台限制）。

## ReminderAlarmService（响铃前台服务，修复轮新增）

- `foregroundServiceType="mediaPlayback"`（正在播放闹钟铃声即媒体播放的本义；12+ 后台启动前台服务的豁免恰好覆盖「精确闹钟触发」路径）。
- `ACTION_START(ids, day, minute, notification)`：startForeground（5s 时限先行）→ `ReminderRinger` 响铃 → CPU 唤醒锁（60s+余量，息屏且页面未拉起时铃声不断续）→ 60s 超时任务 → 亮屏补拉 `TodoReminderActivity`（runCatching：无障碍服务运行时进程拥有后台启动 Activity 豁免；失败则横幅+铃声兜底。与 FSI 并发拉起由 Activity singleTop + onNewIntent 去重）。
- `ACTION_STOP_RING`（页面任意交互发送）：人已到场，停铃；服务保留待 FINISH 收口。
- `ACTION_FINISH`（Activity.finish() 统一收口发送：关闭按钮/返回/全勾自动关）：停铃、撤前台通知、自灭。
- 60s 超时收底：按触发口径（day+minute）响前同款查库重算剩余 → 静默通知（notice 渠道、autoCancel、点开带剩余 ids 回全屏页）→ 撤前台通知自灭。
- `START_NOT_STICKY`：闹钟一次性，被杀不复活（下一响由闹钟链路自行触发）。

## TodoReminderActivity（全屏提醒页）

- `@AndroidEntryPoint ComponentActivity` + Compose（FiveSecTheme）；`setShowWhenLocked`/`setTurnScreenOn`（26 用 window flags 兜底）+ keep-screen-on；`excludeFromRecents`；exported=false；`singleTop`（FSI 与亮屏补拉并发去重；第二场提醒复用已开页面走 onNewIntent 强制重装）。
- 数据：intent `EXTRA_TODO_IDS` → `repository.findByIds` → 展示前按「仍启用 && 当天未完成」防御过滤（跨进程窗口期的脏 ids 吸收）。条目展示**完整内容**（多行原文不截断）+ 每条「完成」按钮。
- 感官归服务（页面不再持有 ReminderRinger）；`onUserInteraction` 任一触摸 → `ACTION_STOP_RING`（幂等）；`finish()` → `ACTION_FINISH`（关闭/返回/全勾自动关三路径统一收口）。
- 操作（决策 14）：逐条「完成」按钮（`setCompleted(id, today, true)`，与待办页同款双写）；「关闭」按钮；全勾完自动关页。无贪睡。

## 通知渠道（ReminderChannels，FiveSecApp 启动创建）

| 渠道 | 重要级 | 声音 | 用途 |
|---|---|---|---|
| `todo_reminder_alarm_v2` | HIGH | **静音**（服务 MediaPlayer 循环是铃声唯一来源，防渠道一声+循环双响；显式置空防 ROM 默认声） | 服务前台通知 = FSI 载体：息屏/锁屏直拉全屏页；亮屏高分横幅（服务同时补拉页面） |
| `todo_reminder_notice` | LOW | 无 | 60s 超时后的静默收底通知 |

v1 渠道（`todo_reminder_alarm`，带渠道铃声）已废弃：渠道设置创建后不可变，升级设备启动时删除防双响。

## 权限契约（决策 16 + 修复轮）

| 权限 | 版本 | 请求时机 | 拒绝后果 |
|---|---|---|---|
| `POST_NOTIFICATIONS` | 13+ 运行时 | 首次保存提醒时刻 | 保存成功；通知不显示但**仍响铃震动**（服务+页面补拉不依赖通知可见性；横幅持续提示） |
| 电池优化白名单（修复轮三） | 6.0+ 设置开关 | 横幅「去开启」→ `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 一键弹窗 | 国产 ROM 息屏「应用速冻」可能冻结进程连闹钟广播都收不到（代码层绕不过，市面提醒类应用通行引导；部分 ROM 需再进电池设置选「允许后台运行/无限制」） |
| `USE_EXACT_ALARM` | 12+ 声明即授 | 无需操作（兼容面保留，调度用 setAlarmClock 不依赖） | — |
| `USE_FULL_SCREEN_INTENT` | 14+ 设置开关 | 横幅「去开启」→ `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` | 息屏时不弹全屏页（仍响铃震动，亮屏补拉/横幅兜底） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` / `WAKE_LOCK` / `VIBRATE` / `RECEIVE_BOOT_COMPLETED` / `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 普通权限 | 安装即授 | — |

横幅只在「存在已设提醒的待办 && 对应权限缺失」时出现（不用提醒的用户永不被打扰）；ON_RESUME 重查，补齐即消。
