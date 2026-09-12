# Tasks: 拦截页自定义提示语（栈式一次性提示 + 自定义提示语池）

**Input**: Design documents from `/specs/004-custom-hints/`

**Prerequisites**: plan.md（必需）、spec.md（用户故事）、research.md、data-model.md、contracts/hint-repository.md、contracts/hint-ui.md、quickstart.md

**Tests**: 已在规格 Success Criteria（SC-004）中明确要求自动化测试 → 各阶段包含测试任务。

**Organization**: 任务按用户故事分组，支持独立实现与验证。路径为 Android 单模块项目（`app/src/...`）。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无未完成依赖）
- **[Story]**: 所属用户故事（US1/US2；Setup/Foundational/Polish 无标签）

---

## Phase 1: Setup

- [x] T001 从最新 `master` 创建并切换到 `004-custom-hints` 分支

---

## Phase 2: Foundational — 存储与消费层（阻塞全部用户故事）

**Goal**: `hints` 表 + DAO + `HintRepository`（快照/同步消费/防复活），全部可单测。

- [x] T002 [P] 新建 `app/src/main/kotlin/com/fivesec/app/domain/model/Hint.kt`：`@Entity(tableName = "hints")`（id 自增 PK / text / kind）+ `HintKind`（STACK/POOL 常量）（契约见 contracts/hint-repository.md）
- [x] T003 [P] 新建 `app/src/main/kotlin/com/fivesec/app/data/db/HintDao.kt`：`insert` / `observeByKind(kind) ORDER BY id ASC` / `deleteById(id)` 三方法
- [x] T004 修改 `app/src/main/kotlin/com/fivesec/app/data/db/AppDatabase.kt`：实体加 `Hint`、`version = 4`、新增 `MIGRATION_3_4`（CREATE TABLE hints）并注册；`app/src/main/kotlin/com/fivesec/app/di/AppModule.kt` 加 `provideHintDao`
- [x] T005 新建 `app/src/main/kotlin/com/fivesec/app/data/repository/HintRepository.kt`：`takeNextHint`（锁内弹栈 + consumedPending 防复活 + 异步删库；栈空 `(builtin+pool).random()`）、`pushStackHint`/`addPoolHint`（统一 trim/空白/30 字校验）、`removePoolHint`、`observePool`、`MAX_HINT_LENGTH=30`
- [x] T006 [P] 新建 `app/src/test/kotlin/com/fivesec/app/data/db/HintDaoTest.kt`（Robolectric in-memory）：按 kind 观察升序、按 id 删除
- [x] T007 [P] 新建 `app/src/test/kotlin/com/fivesec/app/data/repository/HintRepositoryTest.kt`（手写 fake DAO 纯 JVM）：LIFO 顺序、栈空回落合并池、池条目参与随机、防复活（observe 重发含已消费行）、push/add 校验（空白拒、超长截断）
- [x] T008 [P] 扩展 `app/src/test/kotlin/com/fivesec/app/data/db/AppDatabaseMigrationTest.kt`：v3 建库插数据 → v4 打开触发 MIGRATION_3_4 → 既有两表数据保留、hints 表可读写

**Checkpoint**: 存储层编译通过、T006/T007/T008 全绿；`./gradlew :app:testDebugUnitTest` 既有用例零回归。

---

## Phase 3: User Story 1 - 拦截页输入与栈式展示 (Priority: P1)

**Goal**: 覆盖层输入行 + 服务接线，实现"保存入栈 → 下次拦截优先展示"。

**Independent Test**: quickstart 场景 A/B（保存→下次展示→消费回落）。

### Implementation for User Story 1

- [x] T009 [P] 在 `app/src/main/res/values/strings.xml` 新增 `blocking_hint_input_hint` / `blocking_hint_save` / `blocking_hint_saved` / `blocking_hint_empty`
- [x] T010 重构 `app/src/main/kotlin/com/fivesec/app/blocking/BlockingOverlay.kt`：构造参数加 `hintText` 与 `onSaveHint`；hintText 展示改为注入文本；提示语下方新增 `[EditText(LengthFilter 30) | 保存]` 行 + 反馈 TextView（GONE 默认，postDelayed 3s 复位）；空白保存显示 `blocking_hint_empty`；输入行不参与 render() 锁定逻辑
- [x] T011 接线 `app/src/main/kotlin/com/fivesec/app/interception/AppBlockerAccessibilityService.kt`：EntryPoint 加 `hintRepository()`；命中分支先 `takeNextHint(内置数组)` 再构造 `BlockingOverlay(hintText=..., onSaveHint={ pushStackHint(it) })`

**Checkpoint**: quickstart 场景 A/B/C 手测通过（保存→展示 LIFO→消费→截断/空白/草稿丢弃）。

---

## Phase 4: User Story 2 - 自定义提示语池管理 (Priority: P2)

**Goal**: 设置页入口 + 管理页（查看/添加/删除），池并入随机抽取。

**Independent Test**: quickstart 场景 D（添加"早点睡觉"后多次拦截可观察到其出现）。

### Implementation for User Story 2

- [x] T012 [P] 在 `app/src/main/res/values/strings.xml` 新增 `settings_hints_entry` / `hints_title` / `hints_add` / `hints_empty` / `hints_input_hint` / `hints_add_confirm` / `hints_dismiss`
- [x] T013 新建 `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/HintListViewModel.kt`（observePool StateFlow / add(trim·空白忽略·30 字截断) / remove）+ 新建 `app/src/test/kotlin/com/fivesec/app/settings/HintListViewModelTest.kt`（截断/空白/删除转发）
- [x] T014 [P] 新建 `app/src/main/kotlin/com/fivesec/app/settings/ui/HintListScreen.kt`：TopAppBar（返回/添加）+ LazyColumn（文本 + Close 删除，与 AppListScreen 同构）+ 空态 + 添加 AlertDialog（OutlinedTextField 单行 30 字限）
- [x] T015 修改 `app/src/main/kotlin/com/fivesec/app/settings/ui/SettingsScreen.kt`（新增 `onOpenHints` 参数，入口插在"拦截应用清单"与"统计"之间）与 `app/src/main/kotlin/com/fivesec/app/MainActivity.kt`（`Routes.HINTS` + 路由注册）

**Checkpoint**: quickstart 场景 D 通过；全部单测绿。

---

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T016 [P] 更新 `README.md`：功能说明补"拦截页自定义提示（栈式一次性）+ 自定义提示语池"
- [ ] T017 按 `specs/004-custom-hints/quickstart.md` 场景 A~F 完成端到端手测并记录结果（含软键盘表现、随机分布抽检）
- [x] T018 提交并推送（2026-09-12：经用户决定直接推送 master `5772aaa`；HTTPS token 403 后 origin 切换为 SSH；CI 由 push master 自动触发）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1（Setup）**: 无依赖，立即开始
- **Phase 2（Foundational）**: 依赖 Phase 1；**阻塞** Phase 3（T011 需 HintRepository）与 Phase 4（T013 需 HintRepository）
- **Phase 3 / Phase 4**: 均依赖 Phase 2；两者文件不重叠（blocking/interception vs settings/MainActivity），但 T009/T012 同改 strings.xml → 串行更稳妥；US1 先行（P1，MVP）
- **Phase 5（Polish）**: 依赖全部故事完成

### User Story Dependencies

- **US1（P1）**: Phase 2 完成即可实现，**即为可交付 MVP**（栈式提示完整闭环）
- **US2（P2）**: 依赖 Phase 2；实现后随机池自动并入 US1 的回落路径（无需再改 blocking 侧）

### Within Each User Story

- 存储层（实体/DAO）→ Repository → 服务接线/VM → UI 的顺序实现
- 测试与实现同故事内完成（SC-004 要求）
- 单一实现者串行推进；[P] 仅在确认无同文件冲突时利用

### Parallel Opportunities

- Phase 2 的 T002 / T003 与 T006 / T007 / T008 测试可穿插并行
- T009（US1 文案）、T012（US2 文案）与对应 UI 实现可并行
- T014（HintListScreen）与 T015（路由/入口）在 T013 后可并行

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1 + Phase 2 → 存储与消费层就绪
2. Phase 3（US1）→ 拦截页输入 + 栈式展示闭环，**即为可交付 MVP**
3. 验证后继续 US2（管理页 → 池并入随机）

### Incremental Delivery

- US1 独立交付"写句话给未来自己"核心价值；US2 扩充随机池弹药——每步都可独立验收回归。

---

## Notes

- 数据红线：不得引入 `fallbackToDestructiveMigration`；`interception_events`/`target_apps` 零触碰（FR-011）
- 一次性消费红线：同一栈顶至多被 `takeNextHint` 返回一次（FR-003，T007 防复活用例守住）
- 覆盖层状态机与按钮锁定逻辑是回归锚点，除插入输入行外不得触碰（FR-009/FR-011）
- 任务完成后按 speckit 流程可运行 `/speckit-analyze` 做产物一致性检查
