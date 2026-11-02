# Spec: 单应用时段分布（specs/018）

## 背景

specs/011 的 24 桶时段分布回答「几点最容易破防」，但口径是**全部应用合计**——「几点刷 B 站、
几点刷抖音」被平均掉了。本 spec 给拦截二级页的应用卡加**下钻**：点应用卡展开该应用自己的
24 桶分布，复用既有柱状图组件与纯函数，回答「我几点在刷哪一个」。

## 需求

1. 拦截二级页：应用卡整卡可点，点击在卡下方展开/收起该应用的时段分布图（同一 24 桶样式、
   峰值高亮规则同全局卡）；同时只展开一个应用（点另一个自动收起）。
2. 口径与全局卡一致：计入该应用全部结局事件、本地时区分桶、所选周期区间半开、Kotlin 端聚合。
3. 展开态跨周期切换保持（换周期若仍有数据照常渲染，无数据渲染全零图）；收起态与现状一致。
4. 应用卡的点击语义此前无冲突（不可点），本次新增不破坏现有行内开关与菜单。

## 方案

- **DAO**：`observeTimestampsBetween` 泛化——新增 `observeTimestampsByPackageBetween(pkg, start, end)`
  （原查询保留供全局卡）。
- **VM**：`_hourPkg MutableStateFlow<String?>` + `hourDistributionFor: StateFlow<List<Int>>`
  （flatMapLatest on selectedPeriod + pkg，WhileSubscribed 同既有口径）。
- **UI**：`AppRangeStatCard` 增 `expanded: Boolean + onClick`，展开时其下挂 `HourDistributionCard`
  （组件从 StatsDetailPages 复用，无复制）；卡头加 Chevron 旋转指示可展开。

## 验收

- 只造抖音 23 点事件 → 抖音卡展开后 23 桶有柱、B 站卡展开全零；
- 展开抖音后点 B 站：抖音收起、B 站展开；周期切换后展开态保持且数据随区间重算；
- 收起后二级页与现状视觉一致。

## 红线沿用

零新表零迁移；分桶仍走 `HourDistribution` 纯函数（SQLite localtime 不可测的既有决策）；
今日四卡锚点无涉。
