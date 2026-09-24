---
description: "Task list: 提示语功能退役（009）"
---

# Tasks: 提示语功能退役——待办成为拦截页唯一缓冲内容

**Input**: Design documents from `/specs/009-retire-hints/`（spec/plan/research/data-model/contracts/quickstart）

**Tests**: 仓库验证标准 ② 要求行为改动配测试——本特性包含测试任务（TodoRepository 快照四态、迁移 v8→v9）。

**Organization**: 按 spec 用户故事分阶段。注意：这是退役型特性，故事间存在同文件顺序依赖（见 Dependencies），**按 T001→T024 顺序执行**即可。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属用户故事（US1 移除提示语行 / US2 空态引导 / US3 Tab 退役 / US4 数据零破坏）

---

## Phase 1: Setup（基线验证）

- [x] T001 基线验证：当前 master 编译绿——实际经本机缓存的 Gradle 8.9 发行版执行（仓库 `gradlew.bat` 为提示性存根，不执行构建）；基线 BUILD SUCCESSFUL

**Checkpoint**: 环境可用，开始改动。

---

## Phase 2: Foundational（共享前置：快照类型）

- [x] T002 [P] 新增 `TodayTodosSnapshot(items, anyEnabled)` 到 `app/src/main/kotlin/com/fivesec/app/domain/model/Todo.kt`（契约见 data-model §2；US1 签名与 US2 判定共用）

**Checkpoint**: 领域类型就绪，用户故事可开始（US1→US2 同文件，须顺序）。

---

## Phase 3: User Story 1 - 拦截页移除提示语，待办卡成为唯一缓冲内容 (Priority: P1) 🎯 MVP

**Goal**: 覆盖层不再出现任何提示语文本；`BlockingOverlay` 构造签名收窄为 `TodayTodosSnapshot`。

**Independent Test**: quickstart S1——有未完成待办时拦截，页面仅 标题/待办卡/倒计时/按钮，连续 3 次无轮换文本。

- [x] T003 [US1] `app/src/main/kotlin/com/fivesec/app/interception/AppBlockerAccessibilityService.kt`：EntryPoint 摘 `hintRepository()` 成员与 `takeNextHint` 调用（含 `blocking_exercise_hints` 资源读取与 R import）；`todayTodos` 快照直传
- [x] T004 [US1] `app/src/main/kotlin/com/fivesec/app/blocking/BlockingOverlay.kt`：删 `hint` 构造参数与 `hintText` TextView 及其布局位；`todos` 参数改 `TodayTodosSnapshot`；KDoc 同步（契约 contracts/todo-card-overlay.md §A）
- [x] T005 [US1] `app/src/main/kotlin/com/fivesec/app/data/repository/TodoRepository.kt`：`todayTodos` 返回 `TodayTodosSnapshot`（过滤/排序口径不变，`anyEnabled = snapshot.any { it.isEnabled }`，同锁产出），KDoc 去"对齐 HintRepository"表述与 30 字口径注释

**Checkpoint**: 门①编译绿（执行口径微调：`blocking_exercise_hints` 数组删除挪至 T011——HintListScreen 内置区仍引用该数组，须与其同批退役）。

---

## Phase 4: User Story 2 - 无待办时的空态引导（待办卡不再隐藏） (Priority: P1)

**Goal**: 空态二分——无启用条目=引导添加；有启用但今日不轮到=告知。卡片常驻。

**Independent Test**: quickstart S3/S4——停用全部待办触发拦截见引导文案；仅留"每周几（非今天）"见"今天没有轮到的待办"。

- [x] T006 [US2] `app/src/main/res/values/strings.xml`：新增 `blocking_todos_empty_none`（还没有今日待办 · 打开「五秒」添加）与 `blocking_todos_empty_none_due`（今天没有轮到的待办）
- [x] T007 [US2] `app/src/main/kotlin/com/fivesec/app/blocking/BlockingOverlay.kt`：`applyTodos` 实现四态渲染表（CONTENT/ALL_DONE/EMPTY_NONE_ENABLED/EMPTY_NONE_DUE，契约 §B）；`spacerBeforeTodos` 与卡片常驻（删除 GONE 分支）；空态标题色 onSurfaceVariant、条目区 GONE
- [x] T008 [US2] `app/src/test/kotlin/com/fivesec/app/data/repository/TodoRepositoryTest.kt`：既有 `todayTodos` 用例改断言快照 `items`；新增空态二分用例（无启用→items 空 + anyEnabled=false；有启用今日不轮到→items 空 + anyEnabled=true）+ 首用例补 `anyEnabled=true` 断言

**Checkpoint**: quickstart S3/S4 场景逻辑由 T008 行为用例锁定。

---

## Phase 5: User Story 3 - 「提示语」Tab 与管理页退役 (Priority: P2)

**Goal**: 底部导航 4→3 Tab，提示语管理页不可达，`hints_*` 文案清理。

**Independent Test**: quickstart S5——启动应用底部仅 待办/统计/设置，全应用无提示语入口；杀进程重进不崩（恢复兜底）。

- [x] T009 [US3] `app/src/main/kotlin/com/fivesec/app/settings/ui/HomeScreen.kt`：`HomeTab` 摘 `TIPS`（枚举值、Lightbulb 图标 import、when 分支）；`rememberSaveable` 改按名持久化 + `entries.find{} ?: TODOS` 兜底；KDoc 同步
- [x] T010 [US3] 删除 `app/src/main/kotlin/com/fivesec/app/settings/ui/HintListScreen.kt` 与 `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/HintListViewModel.kt`
- [x] T011 [US3] `app/src/main/res/values/strings.xml`：删 `blocking_exercise_hints` 数组、`hints_title/subtitle/add/empty/input_hint/add_confirm/delete/builtin_section/builtin_switch/builtin_save_failed` 与 `tab_tips`；共享键改中性名（`hints_dismiss`→`common_cancel`、`hints_char_count`→`common_char_count`，与既有 common_* 命名段对齐）
- [x] T012 [US3] `app/src/main/kotlin/com/fivesec/app/settings/ui/TodoScreen.kt`：共享键调用点改名（`common_cancel`×2、`common_char_count`×1）；`app/src/main/kotlin/com/fivesec/app/ui/components/Dialogs.kt`：KDoc 去 HintListScreen 字样

**Checkpoint**: 门①编译绿。

---

## Phase 6: User Story 4 - 存量数据零破坏（数据层退役） (Priority: P2)

**Goal**: 提示语数据链路全删；`hints` 表物理保留（v9 空迁移）；DataStore 键停用残留。

**Independent Test**: quickstart S6/S7——覆盖升级后待办/统计原样；`run-as` 查 `hints` 行数不变。

- [x] T013 [US4] 删除 `app/src/main/kotlin/com/fivesec/app/data/repository/HintRepository.kt`（含 `HintCursorStore` 接口）与 `app/src/main/kotlin/com/fivesec/app/data/datastore/DataStoreHintCursorStore.kt`
- [x] T014 [US4] `app/src/main/kotlin/com/fivesec/app/data/db/AppDatabase.kt`：entities 摘 `Hint`、删 `hintDao()`、version=9、新增空 `MIGRATION_8_9`（KDoc 说明：仅让 identity hash 合法化，物理表保留）
- [x] T015 [US4] `app/src/main/kotlin/com/fivesec/app/di/AppModule.kt`：删 `provideHintDao`/`provideHintCursorStore`/`provideBuiltinHintsSetting` 与相关 import；DB provides 注册 `MIGRATION_8_9`
- [x] T016 [US4] `app/src/main/kotlin/com/fivesec/app/data/datastore/SettingsDataStore.kt`：删 `BuiltinHintsSetting` 接口+实现、`hintCursor`/`setHintCursor`、两 Keys；`app/src/main/kotlin/com/fivesec/app/domain/model/AppSettings.kt` 删 `builtinHintsEnabled`；`app/src/main/kotlin/com/fivesec/app/settings/viewmodels/SettingsViewModel.kt` 删默认值
- [x] T017 [US4] 删除 `app/src/main/kotlin/com/fivesec/app/domain/model/Hint.kt` 与 `app/src/main/kotlin/com/fivesec/app/data/db/HintDao.kt`
- [x] T018 [US4] 删除测试 `app/src/test/kotlin/com/fivesec/app/data/db/HintDaoTest.kt`、`app/src/test/kotlin/com/fivesec/app/settings/HintListViewModelTest.kt`、`app/src/test/kotlin/com/fivesec/app/data/repository/HintRepositoryTest.kt`
- [x] T019 [US4] `app/src/test/kotlin/com/fivesec/app/data/db/AppDatabaseMigrationTest.kt`：新增 v8→v9 用例（手建 v8 五表、hints 插 3 行 → v9 打开 → raw query 断言行留存 + 其余表零丢失）；既有 6 条迁移链全部注册 `MIGRATION_8_9` 并把"以 Room v8 打开"口径更新为 v9；v4/v5 用例的 `hintDao` 读写改 raw SQL（009 起无 DAO）

**Checkpoint**: 门①②全绿（门②见 T022 汇总）。

---

## Phase 7: Polish & Cross-Cutting（文档与交付）

- [x] T020 [P] `README.md` 现状化：「已规划退役」→「已退役」；工作原理 step3 去"规划退役中"标注；Tab 口径改三项；待办空态改"常驻空态"；文档索引 009 描述对齐
- [x] T021 [P] `AGENTS.md` 现状化：概述/模块树（datastore 去 DataStoreHintCursorStore、db v9、3-Tab）/协作约束冻结条目改"已落地"口径；术语表循环游标条目改"已退役（表保留不读）"、待办条目空态改四态常驻
- [x] T022 全量验证：门① `:app:assembleDebug` BUILD SUCCESSFUL + 门② `:app:testDebugUnitTest` 全绿（CI 同口径）
- [x] T023 quickstart 手测场景（S1–S7）已交付待用户真机执行——自动化可覆盖部分（快照四态/迁移留存）已由门②锁定
- [x] T024 tasks.md 勾选 + commit + push master

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1** → **Phase 2** → **US1 (P1)** → **US2 (P1)** → **US3 (P2)** → **US4 (P2)** → **Polish**：本特性为退役型改动，同文件强耦合，**总体顺序执行**（T001→T024）。

### 故事间依赖（同文件顺序）

- US1 → US2：共改 `BlockingOverlay.applyTodos` 与 `TodoRepository.todayTodos`（实现时四态渲染随签名收窄一并落地）。
- US3 在 US4 前：`HintListScreen`/`HintListViewModel` 引用 `HintRepository`，先删调用方再删被调方。
- US4 最后：删除数据层后 `HintDaoTest` 等随之退役。

### Parallel Opportunities

- T002/T006（不同文件）可并行；T020/T021（不同文件）可并行。

### MVP Scope

US1 单独交付即达成"拦截页无提示语、待办卡唯一"的产品核心（spec P1 前半）；US2 补空态体验，二者合为完整 P1。

---

## Notes

- 每阶段结束跑一次门①（编译），US2/US4 结束跑门②（测试）。
- 红线自查：无 DELETE/清库（v9 空迁移零 SQL）、`interception_events`/`todo_completions` 零触碰、零依赖变更。
- 环境备注：仓库 `gradlew.bat` 为提示性存根（打印"请用 Android Studio"后 exit 0，不执行构建）；本机验证经 `%USERPROFILE%\.gradle\wrapper\dists` 缓存的 Gradle 8.9 发行版直跑，与 CI（ubuntu + gradle 8.9）同版本口径。
