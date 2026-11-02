# Tasks: 周/月复盘卡（specs/014）

- [ ] 1. 纯逻辑：`util/ReviewTrend`（方向语义枚举 Better↑/Worse↓/Flat—/NoBaseline—，值对 → 趋势；抵制率分母 0 规则内聚）+ 单测（0/0、分母 0、持平、各方向）
- [ ] 2. DAO：`InterceptionEventDao` 补区间总数查询（total/canceled/opened 一次取齐或复用按应用聚合求和——按实现取舍，配 DAO 测试）
- [ ] 3. StatsViewModel：`reviewStats`（档位 StateFlow 周/月 + todayKey 滚动 → 双窗口 combine 既有查询，WhileSubscribed）+ `ReviewStatsUi`
- [ ] 4. UI：`StatsMainPage` 复盘卡（卡头档位分段 + 四行指标 + 方向符号着色），strings.xml 参数化文案
- [ ] 5. 测试：ReviewTrend 单测全绿；StatsViewModelFlowTest 补「复盘双窗口随档位切换」
- [ ] 6. 文档：README 统计节 + AGENTS 术语表补「复盘卡」；quickstart 手测场景
- [ ] 7. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
