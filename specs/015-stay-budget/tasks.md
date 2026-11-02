# Tasks: 回弹层停留预算（specs/015）

- [ ] 1. 纯逻辑：`util/StayBudget`（预算段文案/超额判定/超额分钟，未设=不显示）+ 单测（未设/未超/恰好/超出）
- [ ] 2. DataStore：`stay_budget_minutes` 键（默认 0=不设，夹取 0..600）+ `AppSettings` 字段 + setter；夹取单测（SettingsDataStore 口径沿用）
- [ ] 3. DAO/仓库：`UsageSessionDao` 今日（按 endedAt 半开区间）按包 SUM 查询 + `UsageSessionRepository` 暴露 + DAO 测试
- [ ] 4. 服务：`onOpened` 异步预取今日累计进 @Volatile 字段；`showGuardOverlay` 传预算与累计给回弹层（预算经 InterceptionController 快照读 AppSettings）
- [ ] 5. UI：`UsageGuardOverlay` 副行双段 + 超额红行与二次短震；`InterceptScreen` 守护卡「每日停留预算」预设行 + strings.xml
- [ ] 6. 测试：StayBudget 单测全绿；UsageSessionDao 补区间 SUM 测试
- [ ] 7. 文档：README 守护段 + AGENTS 术语表（使用时长守护条目）+ quickstart 手测场景
- [ ] 8. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
