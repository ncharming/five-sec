# Research: 各应用历史数据（日/周/月/年）+ 统计模块去冗余

**Date**: 2026-09-09 | **Status**: Phase 0 完成，全部决策已定（无 NEEDS CLARIFICATION 残留）

## R1: 数据留存现状审计（需求 1）

**Decision**: 存储层零改造；"自安装起全量留存"由现状保障，固化为红线约束与测试。

**Rationale**: 代码审计结论——
- `interception_events` 只增不删：全库唯一 DELETE 在 `TargetAppDao`（删清单，不删事件）；事件表无任何清理路径。
- `AppModule.provideDatabase` 使用 `addMigrations(MIGRATION_1_2)`，无 `fallbackToDestructiveMigration()`、无 `createFromAsset`——升级走正式迁移，不清库。
- 连续天数（`observeActiveDays`）本就扫描全量历史，证明全量路径可用。

**Alternatives considered**:
- 新增导出/云备份：被否决——用户明确"卸载即清空可接受"，YAGNI。
- 每日汇总表（rollup）：被否决——见 R2。

## R2: 周/月/年聚合实现方案

**Decision**: 泛化既有 SQL 的起点参数（`timestamp >= :rangeStart`），起点在 Kotlin 用 `java.time` 按本地时区计算后传入；**不做 SQL 内日期分组、不做预聚合表**。

**Rationale**:
- 既有 `observeTodayCountsByPackage` 已证明该聚合模式正确（002 上线验证过）；周/月/年仅是换起点，风险最小。
- 起点在 Kotlin 算：`LocalDate.with(DayOfWeek.MONDAY)`（ISO 周一起始，符合国内习惯，周日自然归入当期周）/ `withDayOfMonth(1)` / `withDayOfYear(1)`，语义清晰可单测；SQLite `strftime` 做"周"需自行定义周起点且时区处理易错。
- 个人级事件量（每天个位数~几十条）实时聚合毫秒级完成，预聚合表纯属过度设计。

**Alternatives considered**:
- SQL `strftime('%W'...)` 分组：周定义与本地时区处理脆弱，否决。
- 预聚合/汇总表（每日 rollup 或累计表）：查询快但引入双写一致性问题，且数据量级完全不需要，否决（与 R3 的教训一致）。

## R3: 冗余汇总路径移除（`AppStatistics`）

**Decision**: 彻底删除：`app_statistics` 表（`MIGRATION_2_3: DROP TABLE`）、`AppStatistics` 实体、`AppStatisticsDao`、`InterceptionRepository.updateAppStatistics` 双写、`StatsViewModel.observeAppStatistics` 死代码、`AppModule` 相应 Provider。

**Rationale**:
- 该路径是 002 遗留：无任何 UI 消费（`StatsScreen` 只用 `ui` / `appTodayStats` 两个流），却让每次拦截多一次读-改-写。
- 汇总表只支持"累计"，天然无法回答周/月/年范围问题——与本需求方向相反，留着只会误导后续迭代。

**Alternatives considered**:
- 保留表但停写：半死状态，误导性强，否决。
- 表与实体保留、仅删调用：同上，否决。

## R4: 档位切换 UI 控件

**Decision**: Material3 `SingleChoiceSegmentedButtonRow` + 4 个 `SegmentedButton`（日/周/月/年），放在"各应用历史数据"标题与卡片列表之间。

**Rationale**: 4 个互斥选项是最典型的分段按钮场景；M3 组件与现有设计语言一致，无新增依赖。默认选中"日"，保证首屏与现状一致（US1 场景 1）。

**Alternatives considered**:
- `TabRow`：视觉更重，适合页面级导航而非区块内筛选，否决。
- `FilterChip`：可多选语义，不表达互斥，否决。

## R5: 文案口径（避免"今日"误导）

**Decision**: 应用卡片指标在所有档位统一使用通用文案"拦截 / 打开 / 取消"（去掉"今日"前缀）；顶部四卡保持"今日 ×××"原文案不动。区块标题改为"各应用历史数据"。

**Rationale**: 顶部四卡由"今日概览"语境锚定，无需改动；应用卡片跨四档复用同一组件，文案若带"今日"在周/月/年档必错（FR-007）。

**Alternatives considered**:
- 按档位动态文案（"本周拦截"…）：4 档 × 3 指标 12 条字符串，收益低维护差，否决。

## R6: ViewModel 结构

**Decision**: `StatsRange` 枚举 + `MutableStateFlow<StatsRange>`；`appRangeStats` 由 `selectedRange` → 计算起点 → DAO Flow 的链式响应。档位切换时基于 `timeProvider.now()` 重算起点（与现状"进入页面定格今日起点"的粒度一致）。

**Rationale**: 与现有 `combine(targets, counts, brandColors)` 结构同构，改动面最小；品牌色缓存机制完全复用。

**Alternatives considered**:
- 每次事件后定时刷新周期边界：现状无此行为，属过度设计，否决（Assumptions 已声明）。
