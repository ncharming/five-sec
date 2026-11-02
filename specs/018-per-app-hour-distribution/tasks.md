# Tasks: 单应用时段分布（specs/018）

- [x] 1. DAO：`observeTimestampsByPackageBetween`（包名过滤+半开区间+升序）+ DAO 测试（跨包隔离/区间排他）
- [x] 2. VM：`hourPkg` 选中态 + `toggleAppHour`（互斥切换）+ `hourDistributionFor`（周期+包名双键 flatMapLatest，未选中恒全零不订阅查询，WhileSubscribed）+ StatsViewModelFlowTest「下钻随选择切换」
- [x] 3. UI：`AppRangeStatCard` 可点（Chevron 旋 90° 指示展开态）+ 展开区挂复用的 `HourDistributionCard`
- [x] 4. 测试：DAO/VM 用例全绿
- [x] 5. 文档：README 统计节（时段分布卡描述补下钻）+ AGENTS 术语表（时段分布条目）+ quickstart
- [x] 6. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
