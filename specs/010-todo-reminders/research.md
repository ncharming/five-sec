# Research: 到点主动提醒的平台事实（specs/010）

调研结论（Android 官方文档口径 + 本仓库已核实现状），决策依据见 plan.md。

## 本仓库现状（已核实）

- Manifest 零提醒基建：无 `POST_NOTIFICATIONS`/`SCHEDULE_EXACT_ALARM`/`USE_FULL_SCREEN_INTENT`/`RECEIVE_BOOT_COMPLETED`/`VIBRATE`；无 WorkManager/前台服务依赖。
- `todos` 全列日期粒度（yyyy-MM-dd），无任何时刻列；Room v9；AGENTS.md 明言现状「无通知」——本特性是 App 第一个通知/闹钟能力。
- target/compileSdk 35、minSdk 26：26 起 `AlarmManager.setExactAndAllowWhileIdle`/`setAndAllowWhileIdle`/`VibrationEffect.createWaveform`/`NotificationChannel` 全部可用，无需版本分叉兜底（亮屏 flags 26→27 一处分叉）。

## 精确闹钟（Android 12+ / API 31+）

- `SCHEDULE_EXACT_ALARM`（声明制）默认对 target 31+ 的应用关闭，用户去「设置 → 应用 → 特殊权限 → 闹钟和提醒」开启；`AlarmManager.canScheduleExactAlarms()` 查询；应用可发 `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 直接跳该应用的开关页。
- `USE_EXACT_ALARM` 安装即授，但官方口径限定闹钟钟/日历类应用——「待办提醒」不蹭（决策 4）。
- 未授权时 `setExactAndAllowWhileIdle` 抛 `SecurityException`——降级路径必须先查 `canScheduleExactAlarms()`。
- API < 12 无此限制，恒精确。

## Full-screen intent（FSI）

- 通知的 `fullScreenIntent` 是后台拉起全屏页的**官方唯一豁免通道**（绕过后台 Activity 启动限制）；声明 `USE_FULL_SCREEN_INTENT`（normal 权限，<14 安装即授）。
- Android 14+（API 34）：默认只给通话/闹钟类应用放行 FSI；其余 `NotificationManager.canUseFullScreenIntent()` 为 false 时 FSI 静默降级为普通高分横幅；用户可在「设置 → 应用 → 通知 → 全屏显示」手动放行；`Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT` 可直达系统开关页。
- Android 13+（API 33）：`POST_NOTIFICATIONS` 运行时权限；未授权时通知（含 FSI）一律不显示——纯拒绝态下没有可用的到点 UI，故横幅教育为主、不硬阻断。

## 闹钟生命周期（Android 系统机制）

- AlarmManager 闹钟注册在系统侧，**进程被杀不影响到点触发**（这正是选它而非进程内定时器的理由）。
- **重启清空**：需 `RECEIVE_BOOT_COMPLETED`（普通权限）重排；**应用覆盖更新清空**：`MY_PACKAGE_REPLACED` 重排；**force-stop 全清且收不到任何广播**：唯一不可自愈场景，用户手动重新打开 App 后由启动兜底重排恢复——README 平台限制记载（决策 5）。
- 系统时间被改/时区变更会平移闹钟语义：`TIME_SET`/`TIMEZONE_CHANGED` 广播重排，配合「只排未来」口径自然收敛。

## 响铃与震动

- 铃声走 `ALARM` 音量流：`MediaPlayer.setAudioAttributes(USAGE_ALARM + CONTENT_TYPE_SONIFICATION)`——用户调系统闹钟音量即对五秒生效（类闹钟心智，决策 7）；URI 取 `RingtoneManager.getDefaultUri(TYPE_ALARM)`，个别 ROM 可能为空 → runCatching 只震不响不崩。
- 震动：`Vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 1000, 500), 0))` 循环波形；`VIBRATE` 普通权限。
- 勿扰（DND）：ALARM 流的穿透由用户「勿扰例外」设置决定（`PRIORITY_CATEGORY_ALARMS`），应用无法静默代开——不做穿透、README 说明（决策 10）。

## Hilt 与组件注入

- `@AndroidEntryPoint` 官方支持 Activity 与 BroadcastReceiver（receiver 在 `onReceive` 前完成字段注入）；`goAsync()` + 注入的应用级 CoroutineScope 处理挂起收尾（`PendingResult.finish()`），不新建 Service。
- 无障碍服务的 `@EntryPoint` 特例是 Service 限制，与本特性无关；覆盖层「手动构造 ViewModel」形态也仅限 overlay，本特性沿用 Hilt 常规形态。

## 为什么不用 WorkManager / 前台服务

- WorkManager 最早 15 分钟窗口、不保证精确到点——「类闹钟」体感不成立。
- 常驻前台服务=常驻通知+电量税，为一个每日数次的提醒不可接受；OEM 杀后台还会让服务形态更脆。AlarmManager 是此场景的正解。

## CI/构建注意

- 本仓库 CI（ubuntu）用系统 gradle 8.9 直接构建；本地需 Gradle 8.9 + JDK 17 + Android SDK platform-35/build-tools 35.0.0（本机均已就位）。
- 时区陷阱沿用既有约定：Planner 单测显式传 `ZoneId`（CI UTC 与真机行为逐位一致）。
