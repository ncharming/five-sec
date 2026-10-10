# Implementation Plan: 使用时长守护（二次减速带）

**Feature Branch**: `012-usage-session-guard`

**Created**: 2026-10-10

**Status**: Draft

**Objective**: 选「打开」即开会话，连续停留满 N 分钟回弹提醒（继续/结束），会话落 `usage_sessions` 事件，统计侧显示「停留 N 分钟」。拦截链路既有契约零回归。

**Reflection on scope**: 入口减速带（specs/001–011）只对「进」征税，对「停留」零摩擦——「就刷 5 分钟」滑向 1 小时是使用侧最大流失点。本 spec 只补一个维度的摩擦（停留时长），刻意不做：净使用统计（需前台 tick，成本高收益低）、省时估算（等会话数据积累）、每应用独立间隔（配置面爆炸，全局一个数字 + 每应用布尔封顶）。

## Implementation Phases

### Phase 1 - 数据层（可独立编译验证）

Room v11→v12：`target_apps` 加 `sessionGuardEnabled`（默认 1）+ 新表 `usage_sessions`（六列，endReason TEXT 枚举）；`MIGRATION_11_12` 注册进 AppModule 迁移链；`TargetAppDao.setSessionGuardEnabled`、`UsageSessionDao`（insert + 按包名区间聚合）；`SettingsDataStore` 加 `session_guard_minutes`（默认 15，夹取 5–60）；`TargetAppRepository.setSessionGuardEnabled`、`UsageSessionRepository`；`AppSettings.sessionGuardMinutes`。

### Phase 2 - 判定纯逻辑（核心，零 Android）

`interception/UsageSessionTracker`（注入 TimeProvider）：会话状态机——`onOpened(pkg, guardMinutes?)` / `onForegroundSwitched(pkg, guardMinutes?)` / `onTimerFired(foregroundPkg)` / `onGuardContinue(minutes?)` / `onGuardEnded()` / `onGuardUnavailable()` / `onServiceStopped()`，返回 `Action` 列表（ArmTimer/CancelTimer/CloseSession）。常量：闲置关闭 30 分钟、每场上限 3 次回弹。配套 `UsageSessionTrackerTest` 全矩阵。

### Phase 3 - 服务接线与覆盖层

`InterceptionController` 收集 targets 全量 Map + 全局间隔，暴露 `sessionGuardMinutes(pkg): Int?`（守护关→null）与全局值。`blocking/OverlayTodoCard` 从 BlockingOverlay 提取（四态渲染同源）；新 `blocking/UsageGuardOverlay`（标题/副句/待办卡/继续/结束 + 60ms 震动 + addView 失败回调）。`AppBlockerAccessibilityService`：appSwitched 块内路由 onForegroundSwitched（放行早退之前）、OPENED 分支路由 onOpened、guardOverlay 显示期间忽略拦截、handleSessionActions 执行三动作（coroutine delay 计时 / appScope 异步落库）、「结束」走 HOME→250ms 撤层契约。

### Phase 4 - 配置与统计 UI

InterceptScreen：⋯菜单「时长守护」开关 + 「使用时长守护」卡（回弹间隔行 + 预设弹窗 5/10/15/20/30/45/60）；AppListViewModel.setSessionGuard、SettingsViewModel.setSessionGuardMinutes（夹取）。StatsViewModel.appRangeStats 四源 combine 加会话聚合；StatsScreen 应用卡「停留 N 分钟」（>0 才显示）。

### Phase 5 - 验证与收尾

迁移测试 v11→v12（手建 v11 库→打开→断言列默认/新表/零丢失）、DAO 聚合测试、README/AGENTS 同步、本地双绿、推送 CI 绿。

## Risk Assessment

- **无障碍事件时序**（高影响/低概率）：改动全部在 appSwitched 块内追加路由 + 早退条件加 `|| guardOverlay != null`，不动三个放行标记语义；`InterceptionControllerTest` + BlockingViewModelTest 回归兜底。
- **计时与真实离开竞速**（低影响）：timer 到点时前台已切走的极端次序由 `onTimerFired(foregroundPkg)` 前台校验兜住（返回 null 静默）。
- **OEM 拦 overlay**（低概率/已知模式）：addView 失败→onGuardUnavailable→本场停守护，沿用「宁放弃不打扰不闪退」。
- **迁移**（高影响/低风险）：加列带默认 + 建新表，无重命名无删除；手建库测试断言逐列。
