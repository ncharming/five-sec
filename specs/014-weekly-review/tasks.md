# Tasks: 周/月复盘卡（specs/014）

- [x] 1. 纯逻辑：`util/ReviewTrend`（方向语义枚举 Better↑/Worse↓/Flat—/NoBaseline—，值对 → 趋势；抵制率分母 0 规则内聚）+ 单测（0/0、分母 0、持平、各方向）
- [x] 2. DAO：零新增——复用按应用聚合区间查询在 VM 求和（tasks 备选口径「复用聚合求和」落地）
- [x] 3. StatsViewModel：`reviewStats`（档位 StateFlow 周/月 + todayKey 滚动 → 双窗口 combine 既有查询，WhileSubscribed）+ `ReviewCardUi/ReviewMetricUi`
- [x] 4. UI：`StatsMainPage` 复盘卡（卡头档位 chips + 四行指标 + 箭头跟数值/颜色跟语义），strings.xml 参数化文案
- [x] 5. 测试：ReviewTrendTest 全绿；StatsViewModelFlowTest 补「复盘双窗口随档位切换」
- [x] 6. 文档：README 统计节 + AGENTS 术语表补「复盘卡」；quickstart 手测场景
- [x] 7. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
