---

description: "Task list for feature: 添加应用搜索 + 应用级今日统计（品牌色展示）"

---

# Tasks: 添加应用搜索 + 应用级今日统计（品牌色展示）

**Input**: Design documents from `/specs/002-app-search-perapp-stats/`

**Prerequisites**: [plan.md](plan.md)（必需）、[spec.md](spec.md)（必需，含用户故事优先级）、[research.md](research.md)、[data-model.md](data-model.md)、[contracts/](contracts/)

**Tests**: 本项目对将被改动的文件已存在测试（`AppListViewModelTest`、`StatsViewModelTest`），且 plan.md 已定义测试策略，因此为每个用户故事包含聚焦的测试任务（沿用 001 既有测试栈：JUnit + Robolectric + coroutines-test）。

**Organization**: 按用户故事分组，便于独立实现与独立测试。本特性为 **brownfield 增强**：复用 001 既有拦截/数据/权限基础设施，**零数据库迁移、零新增模块**。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、不依赖未完成任务）
- **[Story]**: 归属用户故事（US1 / US2）
- 描述含精确文件路径

## Path Conventions

- 单 Android 应用模块：主代码 `app/src/main/kotlin/com/fivesec/app/...`
- 单元测试：`app/src/test/kotlin/com/fivesec/app/...`
- 资源：`app/src/main/res/values/...`
- 构建脚本：`app/build.gradle.kts`、`gradle/libs.versions.toml`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 引入本特性唯一的新增依赖（品牌色提取所需 Palette）；为 brownfield 改动建立绿色基线。

- [X] T001 [P] 在 `gradle/libs.versions.toml` 新增 `androidx.palette:palette-ktx` 依赖项（版本对齐 AndroidX BOM 或既有 AndroidX 库版本）
- [X] T002 在 `app/build.gradle.kts` 的 `dependencies` 块加入 `implementation(libs.androidx.palette.ktx)`（别名以 T001 实际命名为准），Gradle 同步并确认编译通过（依赖 T001）
- [ ] T003 [P] 建立绿色基线：在改动前运行 `./gradlew :app:testDebugUnitTest`，确认 `app/src/test/kotlin/com/fivesec/app/` 下 001 既有测试全部通过（作为回归参照）

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 跨用户故事的阻塞前置项。

**说明**：本特性两条故事（搜索 / 应用级统计）**完全独立**，均直接构建在 001 既有基础设施之上，无共享阻塞前置。品牌色提取器（US2 专用）与搜索过滤（US1 专用）互不依赖。故本阶段无额外任务——Phase 1 完成后即可并行进入任一用户故事。

**Checkpoint**: Phase 1 完成 → 两条用户故事均可独立开始。

---

## Phase 3: User Story 1 - 添加应用搜索 (Priority: P1) 🎯 MVP

**Goal**: 在应用清单的"添加应用"选择器顶部加入搜索框，按名称（不分大小写）+ 包名实时过滤已安装应用；并把应用枚举移出主线程，满足 200+ 应用下 0.5s 过滤。

**Independent Test**: 进入应用清单 → 点 `+` → 搜索框输入"微博" → 候选列表实时过滤为匹配项 → 选择后微博进入清单。

**契约**: [contracts/add-app-search.md](contracts/add-app-search.md) · **数据**: [data-model.md](data-model.md#搜索相关数据)

- [X] T004 [P] [US1] 重构 `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/AppListViewModel.kt`：将 `installedApps()` 的 `PackageUtil.installedUserApps(pm)` 枚举移至 `Dispatchers.IO` 协程，结果缓存为 `StateFlow<List<PackageUtil.InstalledApp>>`（含 loading 状态），供 UI 订阅；移除主线程同步枚举
- [X] T005 [P] [US1] 在 `app/src/main/kotlin/com/fivesec/app/util/PackageUtil.kt` 新增纯函数过滤逻辑：`filterInstalledApps(candidates, addedPackageNames, query)` —— `query` 为空返回全部未添加项；非空按 `label` 不分大小写子串匹配 **或** `packageName` 子串匹配；标记已在清单中的项为 `isAdded`（置灰而非删除），满足 FR-005
- [X] T006 [P] [US1] 在 `app/src/main/res/values/strings.xml` 新增搜索占位文案（如"搜索应用名称或包名"）与空状态文案（如"未找到匹配的应用"）
- [X] T007 [US1] 更新 `app/src/main/kotlin/com/fivesec/app/settings/ui/AppListScreen.kt` 的添加选择器：在 `LazyColumn` 上方加入搜索 `TextField`（绑定本地 `query` state）；用 T005 的纯函数实时过滤 T004 的缓存列表；结果为空时显示 T006 的空状态；已添加项置灰且不可点击；保留既有"完成"按钮与 3 上限逻辑（依赖 T004、T005、T006）
- [X] T008 [P] [US1] 更新/新增测试 `app/src/test/kotlin/com/fivesec/app/settings/AppListViewModelTest.kt`：覆盖纯函数过滤（名称/包名、大小写、空查询、去重标记）与已安装应用 IO 加载的 StateFlow 行为（可注入 fake PackageManager/Repository）

**Checkpoint**: User Story 1 独立可用——添加任意应用可通过搜索秒级定位（SC-001/002/003）。

---

## Phase 4: User Story 2 - 应用级今日统计（品牌色展示）(Priority: P2)

**Goal**: 为每个目标应用在统计页渲染一张品牌色卡片，显示今日拦截次数与今日打开次数；品牌色取自应用图标代表色，失败/对比度不足回退健康绿。

**Independent Test**: 今天对小红书拦截 3 次（打开 2 次）、对抖音拦截 1 次（打开 0 次）→ 统计页小红书卡片显示"今日拦截 3 / 今日打开 2"、抖音卡片"今日拦截 1 / 今日打开 0"，且两卡片用各自品牌色。

**契约**: [contracts/perapp-stats-card.md](contracts/perapp-stats-card.md) · **数据**: [data-model.md](data-model.md#新增派生视图瞬态不持久化)

- [X] T009 [P] [US2] 在 `app/src/main/kotlin/com/fivesec/app/data/db/InterceptionEventDao.kt` 新增 2 个查询：`observeTodayCountByPackage(startOfDay, packageName): Flow<Int>`（该应用今日全部事件数=今日拦截）与 `observeTodayCountByPackageAndOutcome(startOfDay, packageName, outcome): Flow<Int>`（今日 `OPENED` 数=今日打开）；时间下界沿用既有 `timestamp >= startOfDay` 模式
- [X] T010 [P] [US2] 新建 `app/src/main/kotlin/com/fivesec/app/util/AppBrandColorExtractor.kt`：`@Inject @Singleton`（构造注入 `@ApplicationContext Context` 取 PackageManager）；`suspend fun colorFor(packageName): Color` —— `PackageUtil.icon` → `drawable.toBitmap()` → `Palette.from(...).generate()` → `getDominantColor`（`getVibrantColor` 备选）→ Compose `Color`，全程 `Dispatchers.IO`；按 `packageName` 内存缓存；提取失败/对比度不足回退 `Primary`（`0xFF00A86B`，见 `ui/theme/Color.kt`）。无需改动 `di/AppModule.kt`（Hilt 构造注入自动提供）
- [X] T011 [US2] 在 `app/src/main/kotlin/com/fivesec/app/data/repository/InterceptionRepository.kt` 新增 `AppTodayStats` 视图类型（`packageName, appName, brandColor, todayInterceptions, todayOpened`）与方法 `observeAppTodayStats(startOfDay): Flow<List<AppTodayStats>>`：合并 `TargetAppRepository.observeAll()` 与 T009 的 2 个查询，并经 T010 注入 `brandColor`（依赖 T009、T010）
- [X] T012 [US2] 更新 `app/src/main/kotlin/com/fivesec/app/settings/viewmodels/StatsViewModel.kt`：暴露应用级今日统计的 `StateFlow<List<AppTodayStats>>`（用 `DateUtil.startOfDayMillis(timeProvider.now())` 作下界），与既有总体统计并存（依赖 T011）
- [X] T013 [US2] 更新 `app/src/main/kotlin/com/fivesec/app/settings/ui/StatsScreen.kt`：在既有总体统计区之外，为每个 `AppTodayStats` 渲染一张卡片——`appName` 标题、「今日拦截」/「今日打开」两个数值、以 `brandColor` 为主题色；按品牌色相对亮度自适应黑/白前景保证 WCAG AA 对比度；今日无数据的应用显示 0/0；不阻塞既有统计（依赖 T012）
- [X] T014 [P] [US2] 更新/新增测试：`app/src/test/kotlin/com/fivesec/app/settings/StatsViewModelTest.kt` 覆盖 `observeAppTodayStats` 的 Flow 聚合（今日拦截=全部事件、今日打开=`OPENED`、跨包名正确）；并对 `AppBrandColorExtractor` 的回退逻辑（提取失败→健康绿）补单元测试（可注入 fake PackageManager/图标）

**Checkpoint**: User Story 2 独立可用——统计页为每个目标应用展示品牌色今日卡片，数值与事件表一致（SC-004/005/006/007）。

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 跨两条故事的收尾与验证。

- [ ] T015 [P] 按 [quickstart.md](quickstart.md) 在真机/模拟器跑完 3 个端到端验证场景（添加搜索、应用级今日统计、品牌色兜底）
- [ ] T016 [P] 可访问性复核：在浅色/深色主题下核对 `app/src/main/kotlin/com/fivesec/app/settings/ui/StatsScreen.kt` 品牌色卡片文字对比度（WCAG AA ≥ 4.5:1），必要时调整 T013 的前景色/兜底判定
- [ ] T017 全量验证：运行 `./gradlew :app:testDebugUnitTest` 覆盖 `app/src/test/`，运行 `./gradlew :app:connectedDebugAndroidTest` 覆盖 `app/src/androidTest/`，并 `./gradlew :app:assembleDebug` 构建通过

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**：无依赖，立即开始。T002 依赖 T001；T003 可与 T001 并行。
- **Foundational (Phase 2)**：无共享阻塞项；Phase 1 完成即解锁两条故事。
- **User Stories (Phase 3 & 4)**：均仅依赖 Phase 1；两条故事**彼此独立**，可并行或按 P1→P2 顺序。
- **Polish (Phase 5)**：依赖相关用户故事完成。

### User Story Dependencies

- **US1（搜索，P1）**：仅依赖 Phase 1；不依赖 US2。→ MVP 候选。
- **US2（应用级统计，P2）**：仅依赖 Phase 1（Palette 依赖）；不依赖 US1。

### Within Each User Story

- 纯函数/查询/提取器（可并行）→ Repository/ViewModel（依赖前者）→ UI（依赖 VM）→ 测试（[P]，与实现并行编写）。

### Parallel Opportunities

- Phase 1：T001 与 T003 并行。
- US1：T004（VM）、T005（过滤纯函数）、T006（字符串资源）三个不同文件可并行；T008 测试与 T004/T005 并行编写。
- US2：T009（DAO）与 T010（品牌色提取器）不同文件可并行；T014 测试与 T009/T010 并行编写。
- US1 与 US2 整体可由两人并行（无文件冲突）。

---

## Parallel Example: User Story 1

```text
# 并行启动 US1 的三个独立文件：
Task: "T004 重构 AppListViewModel.kt：IO 加载已安装应用并缓存为 StateFlow"
Task: "T005 在 PackageUtil.kt 新增纯函数 filterInstalledApps"
Task: "T006 在 strings.xml 新增搜索占位与空状态文案"

# 汇聚后再做 UI 集成（依赖上述三者）：
Task: "T007 更新 AppListScreen.kt 选择器：搜索框 + 过滤 + 空状态 + 已添加置灰"
```

## Parallel Example: User Story 2

```text
# 并行启动 US2 的两个独立文件：
Task: "T009 在 InterceptionEventDao.kt 新增 2 个按包名的今日查询"
Task: "T010 新建 AppBrandColorExtractor.kt：图标→Palette→代表色，按包名缓存"

# 汇聚后逐层向上：
Task: "T011 在 InterceptionRepository.kt 新增 AppTodayStats 与 observeAppTodayStats"
Task: "T012 在 StatsViewModel.kt 暴露应用级今日统计 StateFlow"
Task: "T013 在 StatsScreen.kt 渲染品牌色今日卡片"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1（Palette 依赖 + 绿色基线）。
2. 完成 Phase 3：US1 添加应用搜索。
3. **停下来验证**：按 [contracts/add-app-search.md](contracts/add-app-search.md) 与 [quickstart.md](quickstart.md) 场景 1 独立测试搜索功能。
4. 此时即可发布/演示——添加任意应用已能秒级定位（最小可用增量）。

### Incremental Delivery

1. Phase 1 → 基础就绪。
2. + US1（搜索）→ 独立测试 → 发布/演示（MVP）。
3. + US2（应用级今日统计 + 品牌色）→ 独立测试 → 发布/演示。
4. Phase 5 → 全量验证收尾。

### Parallel Team Strategy

- 开发者 A：US1（AppList + PackageUtil）
- 开发者 B：US2（Stats + DAO/Repo + 品牌色提取器）
- 两人仅共享 Phase 1 的 Palette 依赖，无文件冲突。

---

## Notes

- [P] 任务 = 不同文件、不依赖未完成任务。
- [Story] 标签映射到 spec.md 用户故事，便于追溯。
- 本特性**不改** 001 的拦截/权限/种子/总体统计；`AppStatistics` 实体与数据库 schema **不变**，无迁移。
- `AppBrandColorExtractor` 与 US2 的 ViewModel 等通过 Hilt 构造注入，无需手写 `@Provides`。
- 每个任务或逻辑组完成后提交；在任一 Checkpoint 可停下独立验证该故事。
