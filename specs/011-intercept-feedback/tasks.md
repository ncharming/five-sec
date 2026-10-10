# Tasks: 拦截反馈三件套（specs/011）

- [x] 1. 纯逻辑：`util/ResistRate.kt`（percent：四舍五入、分母 0 → null）+ `util/HourDistribution.kt`（24 桶、ZoneId 注入、BUCKETS 常量）+ 单测（边界：23:59:59.999 / 00:00 / 跨时区同刻 / 空表 / 0% / 100%）
- [x] 2. 状态机：`BlockingViewModel` 新增 `Resisted(count)` 态 + `resistCountProvider` 构造注入（默认 `{ 1 }`）；cancel() → Resisted → 800ms → Finished(CANCELED)；Resisted 后 open/cancel 无效、markInterrupted 提前收尾仍 CANCELED；KDoc 不变量更新
- [x] 3. 测试：`BlockingViewModelTest` 迁移表扩展（成功态携带序号、800ms 落终态、双击防护、打断不翻案、provider 只调一次）；既有「解锁后选择取消」用例按新路径更新
- [x] 4. dao：`InterceptionEventDao` 新增 `countByOutcomeSince`（suspend 一次性）与 `observeTimestampsBetween`（半开区间 Flow）；`InterceptionEventDaoTest` 补区间/空表用例
- [x] 5. repository：`InterceptionRepository` 注入 TimeProvider；抵制镜像（init 播种 + `reseedResistedMirror` + `nextResistedOrdinal`，synchronized 互斥）+ `observeTimestampsBetween` 转发；新建 `InterceptionRepositoryTest`（播种对齐/同日递增/跨日归零/镜像领先边界）
- [x] 6. overlay：`BlockingOverlay` 构造注入 provider；render(Resisted) 成功态（「✓ 已抵制」+ 计数副行 + 按钮置灰 + 60ms 单次震动）；Finished(CANCELED) 保持成功文案防闪回；Vibrator 空设备静默降级
- [x] 7. 接线：服务创建 overlay 传 `resistCountProvider`（求值时取 today，跨零点正确）；`onBlockingFinished` 零改动（HOME→250ms 撤层契约不动）
- [x] 8. stats：`StatsViewModel.hourDistribution`（flatMapLatest 周期重订阅）；`StatsScreen` 今日拦截卡抵制率行（null → 「—」）+ 拦截二级页时段分布卡（Canvas 24 柱、峰值高亮、5 点轴标、共 N 次）
- [x] 9. 文案：strings.xml 新增 blocking_resisted / blocking_resisted_count / stats_resist_rate_label / stats_resist_rate_none / stats_hour_distribution / stats_hour_total / stats_hour_peak / stats_hour_label
- [x] 10. 文档：README（工作原理第 4 步成功态、统计段抵制率与时段分布）+ AGENTS.md（术语表：成功态/抵制率/时段分布；blocking/ 包描述）+ tasks.md 勾选
- [x] 11. 验证：`./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` 全绿（243 tests, 0 failed）；quickstart.md 手测场景过（待真机）
- [x] 12. commit（Conventional Commits 中文主题）推 master，CI 绿（run #88 暴露既有 AppListViewModelTest 偶发空快照问题，e7b4c5b 修为条件等待；run #89 success）
