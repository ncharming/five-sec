# Contract: 提醒排程与触发链路（specs/010）

## 组件图（reminder/ 新包，全部新代码；拦截/覆盖层零改动）

```
todos 表 ──observeAll──▶ TodoReminderCoordinator ──nextTriggerAt──▶ TodoReminderScheduler ──▶ AlarmManager（单一闹钟）
                              ▲                                                            │ 到点
                              │ rescheduleNow()                                             ▼
 BootReceiver(BOOT/TIME/TZ/PKG_REPLACED) ─┐                              TodoReminderReceiver
 FiveSecApp.onCreate（进 App 兜底）───────┘                              │ isDueForMinute 反查（触发分钟）
                                                                         ▼
                                              full-screen intent 通知（alarm 渠道）
                                                                         ▼
                                              TodoReminderActivity（全屏页 + ReminderRinger 响铃震动）
                                                │ 逐条勾完成 ──▶ TodoRepository.setCompleted（既有双写）
                                                │ 60s 无操作 ──▶ 停铃 + 静默通知（notice 渠道）
```

## TodoReminderScheduler（AlarmManager 封装）

- `scheduleNext(triggerAtMillis: Long)`：单一 PendingIntent（requestCode 固定、FLAG_IMMUTABLE|UPDATE_CURRENT），intent 携带 `EXTRA_TRIGGER_AT = triggerAtMillis`。
- 精确策略：`canScheduleExactAlarms()`（12+，以下恒精确）真 → `setExactAndAllowWhileIdle(RTC_WAKEUP, …)`；假 → `setAndAllowWhileIdle(RTC_WAKEUP, …)`（降级仍响，可能漂移数分钟——决策 4）。
- `cancel()`：无条件取消 PendingIntent（幂等）。
- RTC_WAKEUP：到点唤醒设备（类闹钟语义），与设备睡眠态无关。

## TodoReminderCoordinator（重排唯一入口）

- `@Singleton`；init 收集 `TodoRepository.observeAll()`：每次发射 → `TodoReminderPlanner.nextTriggerAt(todos, timeProvider.now(), ZoneId.systemDefault())` → null 则 cancel、否则 scheduleNext。**任何写路径（改时刻/规则/启停/删除/复活/勾选）经 Room 重发自动重排，无遗漏角落。**
- `suspend rescheduleNow()`：`allTodosOnce()` 从库直读（不信内存快照——开机广播时收集器可能未就绪）后同口径重排；供 BootReceiver / FiveSecApp 启动兜底调用。
- 串行执行（收集协程天然串行；rescheduleNow 亦在同一 scope 排队），无并发排程竞态。

## TodoReminderReceiver（到点触发）

1. 读 `EXTRA_TRIGGER_AT`（防御：无 extra → 仅重排后返回）。
2. `goAsync()` + 注入 scope：按**触发时刻**（非当前时间）换算本地 `reminderDay`（yyyy-MM-dd）与 `minute`（HH:mm）。
3. `allTodosOnce().filter { TodoReminderPlanner.isDueForMinute(it, reminderDay, minute) }` —— 响前查库（决策 13）：排程后到点前发生的勾选/停用/删除全部被最新库态吸收。
4. 空集 → 不响（静默，例如到点前刚勾完成）；非空且通知可用（<33 或已授权）→ 发 full-screen intent 通知（alarm 渠道、CATEGORY_ALARM、priority max、fullScreenIntent 指向 `TodoReminderActivity(ids)`）；非空但 13+ 无通知权限 → 跳过展示（横幅已教育，不做无 UI 的幽灵响铃）。
5. 最后 `rescheduleNow()` 排下一响（链式永续）。

## BootReceiver

`BOOT_COMPLETED` / `TIME_SET` / `TIMEZONE_CHANGED` / `MY_PACKAGE_REPLACED` 四动作（均系统保护广播，exported=false）：`goAsync()` → `rescheduleNow()`。覆盖：重启、用户改时钟/时区、应用覆盖更新三种闹钟丢失场景；强停（force-stop）不可恢复是系统机制（README 平台限制）。

## TodoReminderActivity（全屏提醒页）

- `@AndroidEntryPoint ComponentActivity` + Compose（FiveSecTheme）；`setShowWhenLocked`/`setTurnScreenOn`（26 用 window flags 兜底）+ keep-screen-on；`excludeFromRecents`；exported=false（只经 FSI 通知/通知点开进入）。
- 数据：intent `EXTRA_TODO_IDS` → `repository.findByIds` → 展示前按「仍启用 && 当天未完成」防御过滤（跨进程窗口期的脏 ids 吸收）。
- 感官：`ReminderRinger`——`MediaPlayer`（系统默认闹钟铃声 URI、`AudioAttributes(USAGE_ALARM, CONTENT_TYPE_SONIFICATION)`、循环）+ `Vibrator` 波形（1000ms 震 / 500ms 停，循环）。铃声挂了（个别 ROM 无默认闹钟 URI）只震不崩。
- 操作（决策 14）：逐条勾完成（`setCompleted(id, today, true)`，与待办页同款双写）；「关闭」按钮；全勾完自动关页停铃。无贪睡。
- 60 秒无操作（决策 15）：停铃 → 收静默通知（notice 渠道、autoCancel、点开带未完成 ids 回本页）→ 关页。勾选/关闭任一交互即取消超时路径（已停铃走正常关闭）。

## 通知渠道（ReminderChannels，FiveSecApp 启动创建）

| 渠道 | 重要级 | 声音 | 用途 |
|---|---|---|---|
| `todo_reminder_alarm` | HIGH | 系统默认闹钟铃声（USAGE_ALARM AudioAttributes）+ 渠道震动 | FSI 通知载体；14+ 未放行全屏时降级为高分横幅（铃声仍响数秒） |
| `todo_reminder_notice` | LOW | 无 | 60s 超时后的静默收底通知 |

## 权限契约（决策 16）

| 权限 | 版本 | 请求时机 | 拒绝后果 |
|---|---|---|---|
| `POST_NOTIFICATIONS` | 13+ 运行时 | 首次保存提醒时刻 | 保存成功；到点不弹不响（横幅持续提示） |
| `SCHEDULE_EXACT_ALARM` | 12+ 设置开关 | 横幅「去开启」→ `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` | 降级非精确（横幅提示可能不准） |
| `USE_FULL_SCREEN_INTENT` | 14+ 设置开关 | 横幅「去开启」→ `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` | 降级高分横幅 + 渠道铃声数秒 |
| `VIBRATE` / `RECEIVE_BOOT_COMPLETED` | 普通权限 | 安装即授 | — |

横幅只在「存在已设提醒的待办 && 对应权限缺失」时出现（不用提醒的用户永不被打扰）；ON_RESUME 重查，补齐即消。
