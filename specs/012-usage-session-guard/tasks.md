# Tasks: 使用时长守护（二次减速带）

**Feature Branch**: `012-usage-session-guard`

**Created**: 2026-10-10

**Status**: in-progress

## Implementation

- [x] 1. 数据层：TargetApp 加 `sessionGuardEnabled`（默认 true）+ 新实体 `UsageSession`/`UsageSessionEndReason`（TypeConverter）；AppDatabase v12 + `MIGRATION_11_12` + `UsageSessionDao`（insert + observeDurationByPackageBetween）+ `TargetAppDao.setSessionGuardEnabled`；AppModule 迁移链 1→12 + DAO 提供
- [x] 2. 配置源：`SettingsDataStore` 加 `session_guard_minutes`（默认 15，读写夹取 5–60）；`AppSettings.sessionGuardMinutes`；`TargetAppRepository.setSessionGuardEnabled` + `UsageSessionRepository`（record/observe）
- [x] 3. 纯逻辑：`interception/UsageSessionTracker`（TimeProvider 注入；onOpened/onForegroundSwitched/onTimerFired/onGuardContinue/onGuardEnded/onGuardUnavailable/onServiceStopped → Action 列表；常量 IDLE_CLOSE_MS=30min、MAX_GUARD_PER_SESSION=3）
- [x] 4. Tracker 单测矩阵：装/不装计时、切走取消+闲置关、切回重计、到点前台校验、三次上限、继续/结束、SUPERSEDED、不可用停守护、服务停止
- [x] 5. 控制器：InterceptionController 收集 targets 全量 Map + settings（间隔），`sessionGuardMinutes(pkg): Int?` 与全局值；isTarget/enabledTargets 语义零改动 + 既有 InterceptionControllerTest 回归
- [x] 6. 提取 `blocking/OverlayTodoCard`（四态渲染 + TODO_MAX_LINES/TODO_BULLET 迁移）+ 共享震动 helper；BlockingOverlay 改用之（渲染行为逐位不变）
- [x] 7. 新 `blocking/UsageGuardOverlay`：标题/副句/待办卡/继续(实心)/结束(文字)/60ms 震动/addView 失败 onUnavailable；无倒计时
- [x] 8. 服务接线：appSwitched 块末尾路由 onForegroundSwitched（早退之前）；OPENED 分支路由 onOpened；sessionJob 计时；guardOverlay 互斥并入忽略条件；「结束」清标记 + HOME→250ms 撤层；onUnbind/onDestroy 落 SERVICE_STOPPED；handleSessionActions 三动作
- [x] 9. 设置 UI：InterceptScreen ⋯菜单「时长守护」开关 + 「使用时长守护」卡（回弹间隔行 + 预设弹窗 5/10/15/20/30/45/60）；AppListViewModel.setSessionGuard / SettingsViewModel.setSessionGuardMinutes；strings.xml
- [x] 10. 统计：StatsViewModel.appRangeStats 加会话聚合源（AppRangeStatsUi.stayMillis）；StatsScreen 应用卡「停留 N 分钟」（⌈⌉ 取整，>0 才显示）
- [x] 11. 迁移测试 v11→v12（手建 v11 库：加 wasExpired 的 todo_completions → 打开 v12 → 断言守护列默认 1、usage_sessions 可写、五表零丢失）+ UsageSessionDaoTest 聚合/半开区间
- [x] 12. README（工作原理/统计段）+ AGENTS（目录树、术语表：会话/回弹/使用时长守护、DB v12）同步
- [x] 13. 本地 `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿 → commit（Conventional Commits 中文主题）推 master → CI 绿（run #91 暴露 011 遗留的播种后台协程竞速，ee40727 runCatching 修复；run #92 success）→ tasks 勾选收尾
