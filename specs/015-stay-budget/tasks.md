# Tasks: 回弹层停留预算（specs/015）

- [x] 1. 纯逻辑：`util/StayBudget`（isSet/overMinutes：未设/未超 null、恰好触线 0、超出 N）+ 单测（未设/未超/恰好/超出）
- [x] 2. DataStore：`stay_budget_minutes` 键（默认 0=不设，夹取 0..StayBudget.MAX_BUDGET_MINUTES）+ `AppSettings.stayBudgetMinutes` 字段 + setter
- [x] 3. DAO/仓库：`UsageSessionDao.sumDurationSince`（按包 endedAt ≥ 日界求和）+ `UsageSessionRepository.todayStayMillisBefore` + DAO 测试（含恰在日界/跨包隔离/无会话=0）
- [x] 4. 服务：`prefetchTodayStay`（onOpened 异步预取，@Volatile，失败回退仅本场）；`TimerFired` 增 startedAt；`showGuardOverlay` 传预算与累计（预取值+本场墙钟）；InterceptionController 预算快照
- [x] 5. UI：`UsageGuardOverlay` 副行双段 + 触线红行（已达/超出分文案）与二次短震；`InterceptScreen` 守护卡「每日停留预算」预设行（不设/30/60/90/120/180）+ strings.xml + brand_error token
- [x] 6. 测试：StayBudgetTest 全绿；UsageSessionDaoTest 补区间 SUM 测试；StatsViewModelFlowTest 假 DAO 同步
- [x] 7. 文档：README 守护段 + AGENTS 术语表（使用时长守护条目）+ quickstart 手测场景
- [x] 8. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
