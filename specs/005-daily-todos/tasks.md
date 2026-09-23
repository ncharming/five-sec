# Tasks: 每日待办与拦截流程打通（待办模块 + 拦截页待办卡片 + 信息架构重构 + 提示语循环）

**Input**: Design documents from `/specs/005-daily-todos/`

**Prerequisites**: plan.md（必需）、spec.md（用户故事）、research.md、data-model.md、contracts/todo-repository.md、contracts/todo-ui.md、contracts/hint-cycle.md、quickstart.md

**Tests**: 规格 SC-007 明确要求自动化测试 → 各阶段包含测试任务。

**Organization**: 任务按用户故事分组，支持独立实现与验证。路径为 Android 单模块项目（`app/src/...`）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2/US3/US4；Setup/Foundational/Polish 无标签）

---

## Phase 1: Setup

- [x] T001 按仓库惯例直接在 `master` 实施（004 先例：HTTPS token 403 后 origin 为 SSH，push master 即触发 CI）

---

## Phase 2: Foundational — 存储与提示语循环（阻塞全部用户故事）

**Goal**: `todos` 表 + `TodoRepository` + 提示语循环化，全部可单测。

- [x] T002 [P] 新建 `app/src/main/kotlin/com/fivesec/app/domain/model/Todo.kt`：`@Entity(tableName = "todos")`（id 自增 PK / text / isEnabled / lastCompletedDate）+ `TodayTodo` 值类型（契约见 contracts/todo-repository.md）
- [x] T003 [P] 新建 `app/src/main/kotlin/com/fivesec/app/data/db/TodoDao.kt`：`observeAll() ORDER BY id ASC` / `insert` / `updateText` / `deleteById` / `count` / `setEnabled` / `setCompletedDate` 定向 SQL
- [x] T004 修改 `app/src/main/kotlin/com/fivesec/app/data/db/AppDatabase.kt`：实体加 `Todo`、`version = 5`、新增 `MIGRATION_4_5`（CREATE TABLE todos + UPDATE hints stack→pool）并注册；`app/src/main/kotlin/com/fivesec/app/di/AppModule.kt` 加 `provideTodoDao`
- [x] T005 新建 `app/src/main/kotlin/com/fivesec/app/data/repository/TodoRepository.kt`：快照收集 + `todayTodos(today)` 同步读 + `add/rename`（trim/空白拒/30 字截断 + 上限 20）+ `remove/setEnabled/setCompleted`（定向 UPDATE）
- [x] T006 重写 `HintRepository` 为循环语义：删栈机制（`pushStackHint`/栈快照/`consumedPending`/`stackSnapshotSizeForTest`）、`HintKind` 删 `STACK`；`takeNextHint` = [builtin + pool] 按游标取模循环；新建 `HintCursorStore` 端口 + `DataStoreHintCursorStore` 实现 + `SettingsDataStore` 新键 `hint_cycle_cursor`；`AppModule` 绑定
- [x] T007 [P] 新建 `app/src/test/kotlin/com/fivesec/app/data/repository/TodoRepositoryTest.kt`（fake DAO 纯 JVM）：快照过滤/日期映射、add 校验与上限、setCompleted 日期写入与清空、定向 UPDATE 转发
- [x] T008 [P] 重写 `app/src/test/kotlin/com/fivesec/app/data/repository/HintRepositoryTest.kt`：循环顺序/回环、游标跨实例续接、序列缩短取模、addPoolHint 校验转发；删全部栈用例
- [x] T009 [P] `app/src/test/kotlin/com/fivesec/app/data/db/HintDaoTest.kt` 去栈化（kind 只剩 pool 口径）；`HintListViewModelTest` 构造函数补 cursor store 参数
- [x] T010 [P] 扩展 `app/src/test/kotlin/com/fivesec/app/data/db/AppDatabaseMigrationTest.kt`：新增 v4→v5 用例（手建 v4 库含 stack 行 → v5 打开 → todos 表可用 + stack 全部改挂 pool）；既有 v3→v4 用例去栈化

**Checkpoint**: 存储层编译通过、T006~T010 全绿；既有用例零回归。

---

## Phase 3: User Story 1 - 待办页（新「待办」Tab）(Priority: P1)

**Goal**: 待办增删改/启停/今日勾选/每日重置/上限 20 的完整管理页。

**Independent Test**: quickstart 场景 A/C。

### Implementation for User Story 1

- [x] T011 [P] `app/src/main/res/values/strings.xml` 新增待办页文案（tab_todos/todos_title/todos_subtitle/todos_add/todos_empty/todos_input_hint/todos_add_title/todos_edit_title/todos_delete_confirm/todos_quota_full/todos_enabled_count/todos_capacity）
- [x] T012 新建 `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/TodoViewModel.kt`（combine 派生 TodoRow、today MutableStateFlow + refreshToday、add/rename/remove/setEnabled/setCompleted 转发）+ 新建 `app/src/test/kotlin/com/fivesec/app/settings/TodoViewModelTest.kt`（今日口径转发/截断/空白/refreshToday）
- [x] T013 新建 `app/src/main/kotlin/com/fivesec/app/settings/ui/TodoScreen.kt`：PageHeader + 名额行（CapacitySegments 20）+ 空态 + 卡片行（Checkbox/划线/停用弱化/Switch/⋯菜单）+ FiveSecDialog（新建/重命名/删除确认/名额满）

**Checkpoint**: quickstart 场景 A/C 手测通过；单测绿。

---

## Phase 4: User Story 2 - 拦截覆盖层待办卡片 (Priority: P1)

**Goal**: 拦截触发时覆盖层展示今日待办紧凑卡片（只读），同时退役提示语输入链路（US4 前半）。

**Independent Test**: quickstart 场景 B/E-4。

### Implementation for User Story 2

- [x] T014 [P] `strings.xml`：新增 `blocking_todos_title`/`blocking_todos_all_done`/`blocking_todos_more`；删除 `blocking_hint_input_hint`/`blocking_hint_save`/`blocking_hint_saved`/`blocking_hint_empty`
- [x] T015 重构 `app/src/main/kotlin/com/fivesec/app/blocking/BlockingOverlay.kt`：构造参数 +`todos: List<TodayTodo>` −`onSaveHint`；删输入行/保存按钮/反馈链路；按 contracts/todo-ui.md 渲染规则表插入 todoBlock（含前导 spacer 联动 GONE）
- [x] T016 接线 `app/src/main/kotlin/com/fivesec/app/interception/AppBlockerAccessibilityService.kt`：EntryPoint +`todoRepository()`；Block 分支 `todayTodos(DateUtil.todayString(timeProvider.now()))` 注入覆盖层；删 `onSaveHint` 接线

**Checkpoint**: quickstart 场景 B 手测通过；`BlockingViewModel` 状态机零改动。

---

## Phase 5: User Story 3 - 信息架构重构 (Priority: P2)

**Goal**: Tab [待办, 拦截, 提示语, 统计]，默认待办；总开关卡并入拦截页。

**Independent Test**: quickstart 场景 D。

### Implementation for User Story 3

- [x] T017 [P] `strings.xml`：新增 `tab_todos`/`tab_intercept`/`intercept_title`/`intercept_subtitle`；删 `tab_fivesec`；`settings_title/settings_subtitle` 退役（被 intercept_* 取代）；提示语页文案改循环口径（`hints_subtitle`/`hints_empty`）
- [x] T018 重构 `app/src/main/kotlin/com/fivesec/app/HomeScreen.kt`：HomeTab → [TODOS, INTERCEPT, TIPS, STATS]（Checklist 图标），默认 TODOS，映射 TodoScreen/InterceptScreen/HintListScreen/StatsScreen
- [x] T019 新建 `app/src/main/kotlin/com/fivesec/app/settings/ui/InterceptScreen.kt`（总开关卡 + 无障碍状态行 + 应用清单 + 添加弹窗/名额弹窗，双 ViewModel 并挂）；删除 `SettingsScreen.kt`、`AppListScreen.kt`

**Checkpoint**: quickstart 场景 D 手测通过；无残留对已删页面的引用。

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T020 [P] 更新 `README.md`：工作原理（覆盖层而非 BlockingActivity、抑制窗口 5s 口径纠偏）、待办模块、提示语循环、Tab 结构、specs/005 链接
- [x] T021 [P] 更新 `AGENTS.md`：包结构树（TodoScreen/InterceptScreen/DataStoreHintCursorStore）、术语表补「待办/循环游标」、拦截页描述同步
- [x] T022 全量 `./gradlew :app:assembleDebug` + `:app:testDebugUnitTest` 本地全绿（本机 JDK17 + D:/Android/Sdk + Gradle 8.9 发行版）
- [ ] T023 按 `specs/005-daily-todos/quickstart.md` 场景 A~F 完成端到端手测并记录结果（真机）
- [x] T024 提交并推送 master（Conventional Commits 中文主题），CI 绿为交付前提

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（Setup）**: 无依赖
- **Phase 2（Foundational）**: 阻塞 Phase 3/4（待办页与覆盖层都依赖 TodoRepository；覆盖层还依赖 HintRepository 重写完成后的构造签名）
- **Phase 3 / Phase 4 / Phase 5**: Phase 2 完成后可推进；Phase 5 的 TodoScreen 映射依赖 Phase 3，InterceptScreen 本身只依赖既有 VM
- **Phase 6（Polish）**: 依赖全部故事完成

### Parallel Opportunities

- Phase 2 内 T002/T003 与 T007/T008/T009/T010 测试可穿插并行
- T011/T014/T017（strings）与对应 UI 实现可并行
- T020/T021 文档同步与构建验证（T022）可并行

---

## Implementation Strategy

### MVP First (Phase 2 + US1 + US2)

1. Phase 2 → 存储与循环就绪
2. US1（待办页）→ 数据入口闭环
3. US2（拦截页卡片）→ **核心整合价值可交付**

### Incremental Delivery

- US3（Tab 重构）独立可验收；US4（提示语循环）在 Phase 2 已完成主体，Phase 4 顺带收尾输入链路删除与文案口径。

---

## Notes

- 数据红线：不得引入 `fallbackToDestructiveMigration`；`interception_events`/`target_apps` 零触碰（FR-012/FR-013）
- 迁移 SQL 的 `todos` 列定义必须与 Room 生成 schema 逐字一致（迁移测试守住）
- `BlockingViewModel` 状态机与按钮锁定逻辑是回归锚点，本特性零触碰（FR-013）
- 覆盖层创建路径（主线程）不得引入挂起/IO：待办与提示语均为内存快照同步读
