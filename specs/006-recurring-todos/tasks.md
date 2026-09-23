# Tasks: 重复待办（按星期几 / 每 N 天间隔）

**Input**: Design documents from `/specs/006-recurring-todos/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | contracts/ ✅ | quickstart.md ✅

**Tests**: 包含测试任务——仓库验证标准第 2 条要求行为改动必须有对应测试（AGENTS.md），非可选。

**Organization**: 按用户故事分阶段；Phase 2 为全部故事的公共地基（规则建模 + 判定 + 迁移）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 不同文件、无未完成依赖，可并行
- **[Story]**: US1=按星期几 / US2=每 N 天 / US3=老数据升级 / US4=规则随时可改
- 描述含精确文件路径

---

## Phase 1: Setup

- [ ] T001 对齐远端基线：`git fetch origin` 并确认 master == origin/master（远端并行推送是常态，动手前必做）

---

## Phase 2: Foundational（阻塞全部用户故事）

**⚠️ CRITICAL**: 以下未完成前不得开始任何用户故事

- [ ] T002 [P] 新建 `TodoRule` 值类型于 `app/src/main/kotlin/com/fivesec/app/domain/model/TodoRule.kt`：`(repeatType, repeatDays, intervalDays)` 三元组 + `DAILY` 缺省实例 + `weekly(Set<DayOfWeek>)` / `interval(Int)` 工厂；纯 Kotlin 零 Android import；中文 KDoc 说明位掩码口径（bit0=周一…bit6=周日）
- [ ] T003 [P] 新建轮到判定纯逻辑于 `app/src/main/kotlin/com/fivesec/app/util/TodoRecurrence.kt`：`isDue(repeatType, repeatDays, intervalDays, lastCompletedDate, today)` 按 contracts/todo-recurrence.md 判定表实现（含防御口径：today 解析失败→false、last 非法→视同从未完成）；常量 `REPEAT_DAILY/WEEKLY/INTERVAL`、`MIN/MAX_INTERVAL_DAYS`；零时钟读取
- [ ] T004 扩展 `Todo` 实体于 `app/src/main/kotlin/com/fivesec/app/domain/model/Todo.kt`（+repeatType/repeatDays/intervalDays，默认 0）并新增 `TodoDao.updateRecurrence` 定向 UPDATE 于 `app/src/main/kotlin/com/fivesec/app/data/db/TodoDao.kt`（不触碰 text/isEnabled/lastCompletedDate）
- [ ] T005 注册迁移：`app/src/main/kotlin/com/fivesec/app/data/db/AppDatabase.kt` version 5→6 + `MIGRATION_5_6`（3× ALTER TABLE ADD COLUMN ... INTEGER NOT NULL DEFAULT 0，逐字对齐 Room schema）；`app/src/main/kotlin/com/fivesec/app/di/AppModule.kt` 迁移数组追加
- [ ] T006 [P] 新建 `app/src/test/kotlin/com/fivesec/app/util/TodoRecurrenceTest.kt`：判定表 1–8 行全覆盖 + N=2/365 边界 + 跨月/跨年日期差 + 回拨；日期字面量、中文反引号测试名（依赖 T003）

**Checkpoint**: 地基就绪——`assembleDebug` 编译通过、TodoRecurrenceTest 绿，可开始用户故事

---

## Phase 3: User Story 1 - 按星期几重复 (Priority: P1) 🎯 MVP

**Goal**: 待办可设「周一三五」类规则；只在选中日轮到（可勾/计进度/上卡片），其余日灰显「今天不用做」不可勾不消失。

**Independent Test**: 建周几待办 → 选中日可勾且进卡片，非选中日灰显禁勾且不进卡片分母；全不选无法保存。

### Implementation for User Story 1

- [ ] T007 [US1] `app/src/main/kotlin/com/fivesec/app/data/repository/TodoRepository.kt`：`todayTodos(today)` 过滤扩为 `isEnabled && TodoRecurrence.isDue(...)`；`add(text, rule = TodoRule.DAILY)`（兼容既有调用）；新增 `setRecurrence(id, rule): Result<Unit>`（兜底校验：周几空集→failure「每周至少选择一天」；间隔 coerceIn 2..365；经 `updateRecurrence` 定向写）
- [ ] T008 [P] [US1] `app/src/test/kotlin/com/fivesec/app/data/repository/TodoRepositoryTest.kt`：周几命中/未命中的 todayTodos 过滤、setRecurrence 校验失败不落库、定向转发只写三列、add 带规则落库
- [ ] T009 [US1] `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/TodoViewModel.kt`：`TodoRow` +`dueToday`（rows combine 派生中按 today 计算，refreshToday 跨日自动重算）；暴露 `setRecurrence(id, rule)` 转发；`add` 透传 rule
- [ ] T010 [P] [US1] `app/src/test/kotlin/com/fivesec/app/settings/TodoViewModelTest.kt`：dueToday 派生正确（周几命中/未命中）、refreshToday 跨日重算灰显态、setRecurrence 转发（advanceUntilIdleAndFlush 模式）
- [ ] T011 [US1] `app/src/main/res/values/strings.xml` 新增规则文案（todos_rule_title/daily/weekly/interval、todos_rule_dow_1..7、todos_rule_weekly_required、todos_not_due_today 等，见 contracts/todo-ui.md 清单）+ `app/src/main/kotlin/com/fivesec/app/settings/ui/TodoScreen.kt` 行渲染三态表（启用轮到/启用不轮到灰显+标注/停用维持既有弱化；标注条件 `isEnabled && !dueToday`；点行看全文任何状态可用）
- [ ] T012 [US1] `app/src/main/kotlin/com/fivesec/app/settings/ui/TodoScreen.kt` TodoEditDialog 规则区：三选一 SegmentedButton + 周几 7×FilterChip（周一首位）；周几全空 → 保存禁用 + 行内提示（FR-002 主拦截）；保存路径 = rename + 规则变化时 setRecurrence / add(text, rule)

**Checkpoint**: US1 端到端可用——周几规则从编辑到灰显到卡片过滤全部生效，测试绿

---

## Phase 4: User Story 2 - 每 N 天间隔重复 (Priority: P2)

**Goal**: 「每 3 天换床单」类滚动节奏：从未完成恒到期，完成后隔 N 天复活，拖着的持续提醒。

**Independent Test**: 建间隔待办 → 首日即可勾；完成后灰显 N-1 天、第 N 天复活未完成；到期不完成长挂不消失。

- [ ] T013 [US2] `app/src/main/kotlin/com/fivesec/app/settings/ui/TodoScreen.kt` 规则区追加间隔输入：OutlinedTextField 数字 + 越界 coerceIn(2..365) 收敛显示 + 辅助文案「完成后隔 N 天再次出现」（strings: todos_rule_interval_hint / todos_rule_interval_days）（依赖 T012）
- [ ] T014 [P] [US2] `app/src/test/kotlin/com/fivesec/app/data/repository/TodoRepositoryTest.kt` 补间隔用例：interval 收敛（1→2、999→365）、完成当天不轮到、第 N 天复活（依赖 T007）

**Checkpoint**: US1+US2 均独立可用

---

## Phase 5: User Story 3 - 老数据升级零感知 (Priority: P3)

**Goal**: v5 库覆盖升级后条目/状态零丢失，规则自动=每天，行为与升级前一致。

**Independent Test**: 手建 v5 库插三态行 → 全链迁移打开 → 行保留 + 三列默认 0（真机覆盖装走 quickstart 场景 A）。

- [ ] T015 [US3] `app/src/test/kotlin/com/fivesec/app/data/db/AppDatabaseMigrationTest.kt` 新增 v5→v6 用例：手建 v5 四列 todos schema（启用已勾选/启用未勾选/停用三行）→ 注册 MIGRATION_1_2..5_6 打开 → 断言行零丢失、字段不变、三新列均 0、todoDao 可写
- [ ] T016 [P] [US3] `app/src/test/kotlin/com/fivesec/app/data/repository/TodoRepositoryTest.kt` 补「add 缺省规则落库为每天（三列 0）」断言

**Checkpoint**: 迁移回归有测试锚点，老用户路径受保护

---

## Phase 6: User Story 4 - 规则随时可改 (Priority: P3)

**Goal**: 编辑弹窗回填现有规则；保存只发必要写入；切换不清完成状态/锚点。

**Independent Test**: 切换三种规则 → 当日轮到即时重算、lastCompletedDate 不被触碰。

- [ ] T017 [US4] `app/src/main/kotlin/com/fivesec/app/settings/ui/TodoScreen.kt` 编辑弹窗初始回填（按条目现有规则选中类型/周几/N）+ 仅规则变化时调 setRecurrence + 切换类型时保留各规则的弹窗内存勾选（落库只写当前规则，其余两列归零）（依赖 T012、T013）
- [ ] T018 [P] [US4] `app/src/test/kotlin/com/fivesec/app/settings/TodoViewModelTest.kt` 补 rename+setRecurrence 并行转发、规则未变时不发 UPDATE 用例

**Checkpoint**: 四个用户故事全部独立可用

---

## Phase 7: Polish & Cross-Cutting

- [ ] T019 [P] 同步口径：`README.md` 待办段 + `AGENTS.md` 术语表「待办」词条补重复规则与灰显/卡片口径（对齐 005 修订记录风格）
- [ ] T020 全量验证：`gradle :app:assembleDebug :app:testDebugUnitTest` 全绿；涉及判定的测试跑 `--rerun-tasks` 抽验一次
- [ ] T021 提交推送：Conventional Commits 中文主题（`feat(todos): 重复待办规则（按星期几/每 N 天）`）直推 master，确认 CI 绿
- [ ] T022 quickstart.md 场景 A~G 真机手测（`adb install -r` 覆盖升级重点验场景 A 迁移留存）——**用户执行，交付时不勾选**

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup (T001) → Foundational (T002–T006，其中 T004 依赖 T002/T003 的类型，T005 依赖 T004) → US1 (T007–T012，T007 依赖 T002/T003/T004/T005) → US2 → US3/US4 可与 US2 并行（不同关注面）→ Polish

### User Story Dependencies

- **US1**：依赖全部 Foundational；是 MVP
- **US2**：仅依赖 US1 的编辑器骨架（T012）；判定/过滤在地基已备
- **US3**：仅依赖 Foundational（T005）；与 US1/US2 无耦合
- **US4**：依赖 US1+US2 的编辑器（T017 回填两者）

### Parallel Opportunities

- T002/T003 同批并行；T006 与 T004/T005 并行；T008/T010/T014/T016/T018 测试任务与其实现任务分文件可并行推进
- 单人实施按 T001→T002→T003→T004→T005→T006→T007…T021 顺序即满足全部依赖

---

## Implementation Strategy

- **MVP First**：T001–T012 完成即交付「按星期几」完整价值（US1），可独立验证演示
- **Incremental**：US2 → US3 → US4 各自增量不破坏前序；每阶段保持 `assembleDebug + testDebugUnitTest` 绿再前进
- **红线提醒**：迁移禁 `fallbackToDestructiveMigration`；`interception_events`/`hints`/`target_apps` 零触碰；`lastCompletedDate` 只能被勾选/取消触碰（规则更新绝不写它）；判定纯逻辑零时钟读取；测试名中文反引号句
