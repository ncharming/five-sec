# Tasks: 各应用历史数据（日/周/月/年）+ 统计模块去冗余

**Input**: Design documents from `/specs/003-stats-history-ranges/`

**Prerequisites**: plan.md（必需）、spec.md（用户故事）、research.md、data-model.md、contracts/stats-range-query.md、contracts/stats-range-ui.md、quickstart.md

**Tests**: 已在规格 Success Criteria（SC-004）中明确要求自动化测试 → 各阶段包含测试任务。

**Organization**: 任务按用户故事分组，支持独立实现与验证。路径为 Android 单模块项目（`app/src/...`）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3；Setup/Foundational/Polish 无标签）

---

## Phase 1: Setup

- [ ] T001 从最新 `master` 创建并切换到 `003-stats-history-ranges` 分支

---

## Phase 2: Foundational（阻塞全部用户故事）

**Goal**: 周期起点计算与档位枚举——所有故事共同依赖的纯函数层。

- [ ] T002 [P] 在 `app/src/main/kotlin/com/fivesec/app/util/DateUtil.kt` 新增 `startOfWeekMillis` / `startOfMonthMillis` / `startOfYearMillis`（java.time、ISO 周一起始、带 ZoneId 参数，契约见 specs/003-stats-history-ranges/contracts/stats-range-query.md）
- [ ] T003 [P] 新建 `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/StatsRange.kt`：`StatsRange` 枚举（DAY/WEEK/MONTH/YEAR）+ `StatsRange.startMillis(now, zone)` 扩展函数
- [ ] T004 [P] 新建 `app/src/test/kotlin/com/fivesec/app/util/DateUtilTest.kt`：覆盖周一输入、周日输入（返回当期周一）、月初、年初、12-31 跨年、固定 ZoneId 参数化断言

**Checkpoint**: 三个纯函数任务完成后，编译通过且 DateUtilTest 全绿。

---

## Phase 3: User Story 1 - 按日/周/月/年查看各应用历史数据 (Priority: P1)

**Goal**: 统计页应用卡片区支持四档自然周期切换，默认"日"档。

**Independent Test**: 制造跨日历史数据后，切"周"档核对计数 = 本周事件聚合（quickstart 场景 A）。

### Implementation for User Story 1

- [ ] T005 [US1] 泛化 `app/src/main/kotlin/com/fivesec/app/data/db/InterceptionEventDao.kt`：`observeTodayCountsByPackage(startOfDay)` → `observeCountsByPackageSince(rangeStart)`，投影类 `PackageTodayCount` → `PackageRangeCount`（SQL 见 contracts/stats-range-query.md）
- [ ] T006 [US1] 在 `app/src/main/kotlin/com/fivesec/app/data/repository/InterceptionRepository.kt` 将 `observeTodayCountsByPackage` 透传替换为 `observeCountsByPackageSince`
- [ ] T007 [P] [US1] 在 `app/src/main/res/values/strings.xml` 新增 `stats_app_history_section`（各应用历史数据）、`stats_range_day/week/month/year`（日/周/月/年）、`stats_metric_intercepted/opened/canceled`（拦截/打开/取消）；删除不再引用的 `stats_app_today_section`
- [ ] T008 [US1] 重构 `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/StatsViewModel.kt`：`AppTodayStatsUi` → `AppRangeStatsUi`；新增 `selectedRange: StateFlow<StatsRange>`（默认 DAY）与 `selectRange()`；`appTodayStats` → `appRangeStats`（档位切换时以 `timeProvider.now()` 重算起点并重新订阅 DAO Flow；combine(targets, counts, brandColors) 结构保持）
- [ ] T009 [US1] 重构 `app/src/main/kotlin/com/fivesec/app/settings/ui/StatsScreen.kt`：区块标题改用 `stats_app_history_section`；标题下新增 `SingleChoiceSegmentedButtonRow`（4 档，选中项调 `viewModel.selectRange`）；`AppTodayStatCard` → `AppRangeStatCard`，指标标签改用通用文案（stats_metric_*）；顶部四卡区域不动
- [ ] T010 [US1] 扩展 `app/src/test/kotlin/com/fivesec/app/data/db/InterceptionEventDaoTest.kt`：插入跨日/跨周/跨月/跨年事件，断言四档 `observeCountsByPackageSince` 聚合（含 INTERRUPTED 计入 total、opened+canceled ≤ total）
- [ ] T011 [US1] 适配 `app/src/test/kotlin/com/fivesec/app/settings/StatsViewModelTest.kt`：既有今日断言改为 DAY 档语义（数值不变）；新增切换 WEEK 档后计数变化的用例；断言顶部 `ui` 流与档位无关

**Checkpoint**: quickstart 场景 A（四档切换）+ 场景 B 抽检通过；既有全部测试绿。

---

## Phase 4: User Story 2 - 历史数据自首次使用起完整留存 (Priority: P2)

**Goal**: 把"全量留存"固化为可执行验证；守住升级不丢数据红线。

**Independent Test**: 旧版本数据覆盖升级后，"年"档计数与升级前一致（quickstart 场景 C）。

### Implementation for User Story 2

- [ ] T012 [US2] 在 `app/src/test/kotlin/com/fivesec/app/data/db/InterceptionEventDaoTest.kt` 追加"历史完整性"用例：插入跨越数月/数年的早期事件，断言年档聚合包含全部历史（rangeStart=年初 时早期事件计入）
- [ ] T013 [US2] 执行留存审计并在提交说明中记录：全库检索确认 `interception_events` 无 DELETE 路径、Room 构建无 `fallbackToDestructiveMigration`（检索命令与结果记入 specs/003-stats-history-ranges/quickstart.md 的 Notes）

**Checkpoint**: T012 用例绿；审计无异常发现。

---

## Phase 5: User Story 3 - 统计模块去冗余（单一数据源） (Priority: P3)

**Goal**: 删除 `AppStatistics` 冗余汇总路径，统计只依赖事件流水。

**Independent Test**: 覆盖升级后发生新拦截，仅事件表新增记录；统计页数字全部正常（quickstart 场景 D）。

### Implementation for User Story 3

- [ ] T014 [US3] 在 `app/src/main/kotlin/com/fivesec/app/data/repository/InterceptionRepository.kt`：`record()` 收窄为仅 `eventDao.insert(event)`；删除 `updateAppStatistics` / `getAppStatistics` / `observeAllAppStatistics` 与 `AppStatisticsDao` 依赖
- [ ] T015 [P] [US3] 在 `app/src/main/kotlin/com/fivesec/app/di/AppModule.kt` 删除 `provideAppStatisticsDao` 与相关 import
- [ ] T016 [US3] 修改 `app/src/main/kotlin/com/fivesec/app/data/db/AppDatabase.kt`：实体列表移除 `AppStatistics`，`version = 3`，新增 `MIGRATION_2_3`（`DROP TABLE IF EXISTS app_statistics`）并加入 `addMigrations`（保持 MIGRATION_1_2）
- [ ] T017 [US3] 在 `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/StatsViewModel.kt` 删除死代码 `observeAppStatistics` / `AppStatsUi` 及 `AppStatistics` 相关 import
- [ ] T018 [US3] 删除文件 `app/src/main/kotlin/com/fivesec/app/domain/model/AppStatistics.kt` 与 `app/src/main/kotlin/com/fivesec/app/data/db/AppStatisticsDao.kt`
- [ ] T019 [US3] 新建 `app/src/test/kotlin/com/fivesec/app/data/db/AppDatabaseMigrationTest.kt`（Robolectric）：以 v2 schema 手工建库并插入事件与汇总行 → 以 Room v3 打开触发 MIGRATION_2_3 → 断言事件全部保留、`app_statistics` 表不存在
- [ ] T020 [US3] 全库编译并检索清理 `AppStatistics` / `app_statistics` 残留引用（主代码与测试）

**Checkpoint**: quickstart 场景 D（含可选 sqlite3 表检查）通过；全部测试绿。

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T021 [P] 更新 `README.md`：统计模块说明补"各应用历史数据（日/周/月/年）"与数据留存口径（卸载前不清除）
- [ ] T022 按 `specs/003-stats-history-ranges/quickstart.md` 场景 A~E 完成端到端手测并记录结果
- [ ] T023 提交全部改动到 `003-stats-history-ranges` 分支并推送；CI（PR 或 dispatch）构建+单测通过后合并 master

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（Setup）**: 无依赖，立即开始
- **Phase 2（Foundational）**: 依赖 Phase 1；**阻塞**全部用户故事
- **Phase 3~5（US1 → US2 → US3）**: 均依赖 Phase 2；US2 依赖 US1 的 DAO 新查询（T012 复用 T005 产物）；US3 与 US1 在 `InterceptionRepository.kt` / `StatsViewModel.kt` 上有文件重叠 → 按优先级**串行**执行，不并行
- **Phase 6（Polish）**: 依赖全部故事完成

### User Story Dependencies

- **US1（P1）**: Foundational 后即可开始，MVP 核心
- **US2（P2）**: 依赖 T005/T010（查询与测试基建）
- **US3（P3）**: 建议在 US1 验收后执行，避免同文件冲突

### Within Each User Story

- 查询层（DAO）→ 仓库 → ViewModel → UI 的顺序实现
- 测试与实现同故事内完成（SC-004 要求）
- 单一实现者串行推进；[P] 仅在确认无同文件冲突时利用

### Parallel Opportunities

- Phase 2 的 T002 / T003 / T004 三任务完全并行
- T007（strings.xml）与 T005/T006 并行
- T015（AppModule.kt）与 T016（AppDatabase.kt）并行

---

## Parallel Example: Phase 2

```bash
# 三个任务不同文件、零依赖，可同时开始：
#   T002 DateUtil 周期起点函数（util/DateUtil.kt）
#   T003 StatsRange 枚举与映射（settings/viewmodels/StatsRange.kt）
#   T004 DateUtilTest（test/.../util/DateUtilTest.kt）
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 + Phase 2 → 纯函数层就绪
2. Phase 3（US1）→ 四档切换可用，**即为可交付 MVP**
3. 验证后继续 US2（留存固化）→ US3（去冗余）

### Incremental Delivery

- US1 独立交付历史档位价值；US2 无 UI、纯保障；US3 清理不改变用户可见行为——每步都可独立验收回归。

---

## Notes

- 数据红线：任何阶段不得引入 `fallbackToDestructiveMigration` 或对 `interception_events` 的 DELETE（FR-005）
- 顶部四卡相关代码与字符串是回归锚点，除 US3 死代码外不得触碰（FR-004）
- 任务完成后按 speckit 流程可运行 `/speckit-analyze` 做产物一致性检查
