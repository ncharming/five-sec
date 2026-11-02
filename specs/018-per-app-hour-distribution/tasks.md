# Tasks: 单应用时段分布（specs/018）

- [ ] 1. DAO：`observeTimestampsByPackageBetween`（半开区间+包名过滤，升序）+ DAO 测试（跨包隔离/区间排他）
- [ ] 2. VM：`hourDistributionFor`（_hourPkg + selectedPeriod 双键 flatMapLatest，WhileSubscribed）+ StatsViewModelFlowTest 补「选中包随周期重订阅」
- [ ] 3. UI：`AppRangeStatCard` 可点展开（Chevron 指示 + 单展开互斥）+ 展开区挂复用的 `HourDistributionCard`
- [ ] 4. 测试：DAO/VM 用例全绿
- [ ] 5. 文档：README 统计节（时段分布卡描述补下钻）+ AGENTS 术语表（时段分布条目）+ quickstart
- [ ] 6. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
