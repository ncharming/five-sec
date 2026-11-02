# Tasks: 抵制里程碑（specs/016）

- [ ] 1. 纯逻辑：`util/ResistMilestones.isMilestone`（==50 或 %100==0）+ 单测
- [ ] 2. DAO：`InterceptionEventDao.countCanceledAll()`（一次性 COUNT，suspend）+ DAO 测试
- [ ] 3. 仓库：抵制镜像扩展（resistedTotal：init 播种全表 COUNT + nextResistedOrdinal 同锁 +1，返回 `ResistOrdinals(today, total)`）；InterceptionRepositoryTest 补累计口径（播种对齐/不跨日归零/领先自愈）
- [ ] 4. UI：`BlockingViewModel.Resisted` 增 totalResisted（provider 签名升级，默认实现供测试）；`BlockingOverlay` 成功态按谓词追加「累计抵制 M 次」行 + strings.xml
- [ ] 5. 测试：BlockingViewModelTest 补里程碑态不变量（Resisted 携带双值、打断只提前收尾不翻案）
- [ ] 6. 文档：README 拦截反馈段 + AGENTS 术语表（成功态条目）+ quickstart 手测场景
- [ ] 7. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
