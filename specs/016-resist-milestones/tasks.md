# Tasks: 抵制里程碑（specs/016）

- [x] 1. 纯逻辑：`util/ResistMilestones.isMilestone`（==50 或 %100==0，0/负数恒否）+ 单测
- [x] 2. DAO：`InterceptionEventDao.countByOutcome`（全表按结局一次性 COUNT）+ DAO 测试沿用仓库级用例覆盖
- [x] 3. 仓库：抵制镜像扩展 resistedTotal（init 播种全表 COUNT + nextResistedOrdinal 同锁 +1，返回 `ResistOrdinals(today, total)`）；InterceptionRepositoryTest 重写为双序号断言（播种对齐/跨日今日归零累计不断/领先自愈/50 里程碑锚）
- [x] 4. UI：`BlockingViewModel.Resisted` 携带 totalResisted（provider 签名升级 `() -> ResistOrdinals`，domain 纯模型）；`BlockingOverlay` 成功态按谓词追加「累计抵制 M 次」行 + strings.xml；服务注入点签名同步
- [x] 5. 测试：BlockingViewModelTest 适配双值 + 里程碑不变量（打断只提前收尾不翻案照旧全绿）；TodoRepository init 补 runCatching 盔甲（ReminderAlarmServiceTest 实例化真实 Hilt 图后暴露的环境拆除毒化问题，与 InterceptionRepository 同口径）
- [x] 6. 文档：README 拦截反馈段 + AGENTS 术语表（成功态条目）+ quickstart 手测场景
- [x] 7. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
