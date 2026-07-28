# Tasks: App Launch Interceptor with 5-Second Exercise Prompt (五秒 / five-sec)

**Input**: Design documents from `/specs/001-app-intercept-exercise/` ([spec.md](spec.md), [plan.md](plan.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md))

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: 包含。依据 plan.md R8 测试策略与 quickstart.md V4，对核心可测纯逻辑（拦截状态机、去抖/抑制窗口、统计聚合）编写单元/仪器化测试。

**Organization**: 任务按用户故事分组（US1 P1 / US2 P2 / US3 P3），每个故事可独立实现与验证。

**Deferred clarifications（来自跳过的 /speckit-clarify，采用默认值，实现时若需可再确认）**:
- 分发目标 → 默认 **侧载/国内商店**（plan.md R5/R6 仍标注 Play 政策风险）。
- deep link/通知触发的打开 → 默认 **一并拦截**（AccessibilityService 默认行为）。
- 临时放行（snooze）→ 默认 **MVP 不含**。

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 可并行（不同文件、无依赖）
- **[Story]**: 所属用户故事（US1/US2/US3）；Setup/Foundational/Polish 阶段无 Story 标签
- 描述中含确切文件路径
- 包名根：`com.fivesec.app`；源码根：`app/src/main/kotlin/com/fivesec/app/`；JVM 单测：`app/src/test/kotlin/com/fivesec/app/`；仪器化测试：`app/src/androidTest/kotlin/com/fivesec/app/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 初始化 Android Gradle 工程与基础资源（详见 [plan.md](plan.md) Project Structure）

- [X] T001 Create Gradle project root structure per plan.md: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/proguard-rules.pro`
- [X] T002 Configure `app/build.gradle.kts`: Android+Kotlin 插件, Compose enabled, `minSdk 26`/`targetSdk 35`, 依赖（Compose, Material3, Room, DataStore-Preferences, Hilt, Coroutines/Flow, Navigation-Compose, Lifecycle-ViewModel-Compose）, 测试依赖（JUnit, Robolectric, Compose UI Test, UIAutomator, Hilt testing）；**不声明 INTERNET 权限**（FR-010）
- [X] T003 [P] Configure `app/src/main/AndroidManifest.xml`: application name=`FiveSecApp`, 声明 `MainActivity`, `BlockingActivity`, `AppBlockerAccessibilityService`（`android:permission=android.permission.BIND_ACCESSIBILITY_SERVICE` + intent-filter + meta-data 指向 config xml）；不申请网络/账号权限
- [X] T004 [P] Create Material 3 theme + 中文(默认)字符串资源: `app/src/main/res/values/themes.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/colors.xml`, `app/src/main/kotlin/com/fivesec/app/ui/theme/Theme.kt`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 所有用户故事共享的核心基础设施（DI、数据库、设置、领域模型、默认清单、仓储）

**⚠️ CRITICAL**: 本阶段完成前不得开始任何用户故事

- [X] T005 Set up Hilt: `app/src/main/kotlin/com/fivesec/app/FiveSecApp.kt`（`@HiltAndroidApp`），`di/AppModule.kt` 提供数据库/DataStore/PackageManager 依赖
- [X] T006 [P] Create domain models: `domain/model/TargetApp.kt`, `domain/model/InterceptionEvent.kt`（含 `InterceptionOutcome` 枚举 OPENED/CANCELED/INTERRUPTED）, `domain/model/Exercise.kt`（MVP 常量 `kegel_5s`）, `domain/model/AppSettings.kt`, `domain/model/AppStatistics.kt`（应用级别统计：拦截总数、取消率、完成锻炼次数）
- [X] T007 Room database: `data/db/AppDatabase.kt`（实体 TargetApp/InterceptionEvent/AppStatistics）, `data/db/TargetAppDao.kt`（upsert/delete/observe/findByPackage + count() for 3-app limit）, `data/db/InterceptionEventDao.kt`（insert + 按应用聚合查询）, `data/db/AppStatisticsDao.kt`（查询/更新应用级别统计）
- [X] T008 [P] DataStore settings: `data/datastore/SettingsDataStore.kt`（`interception_globally_enabled` 默认 true、`onboarding_completed` 默认 false、`stats_retention_days` 默认 90）
- [X] T009 [P] Default app seed: `data/seed/DefaultAppSeed.kt`（包名 `com.ss.android.ugc.aweme`/`com.xingin.xhs`/`tv.danmaku.bili`, `appName`分别为"抖音"/"小红书"/"哔哩哔哩", `isDefault=true, isEnabled=true`；显示名/图标由 `PackageManager` 解析）— 首次启动写入，使 US1 无需配置 UI 即可工作
- [X] T010 Repositories: `data/repository/TargetAppRepository.kt`（observe/upsert/remove/toggle + isInterceptEnabled 决策 + 3-app limit validation）, `data/repository/InterceptionRepository.kt`（record event + 应用级别统计聚合）

**Checkpoint**: 基础设施就绪——用户故事可开始（并行或按优先级）

---

## Phase 3: User Story 1 - 拦截目标应用并引导 5 秒提肛 (Priority: P1) 🎯 MVP

**Goal**: 检测目标应用进入前台 → 返回桌面并弹出全屏拦截页 → 强制 5 秒提肛倒计时（期间锁定打开/取消）→ 结束后用户可选打开/取消（[contracts/interception-flow.md](contracts/interception-flow.md)）

**Independent Test**: 抖音已在默认清单且全局开关开 → 点击抖音图标 → 出现拦截页（5 秒提肛 + 倒计时）→ 倒计时期间按钮锁定 → 结束后可打开/取消（[quickstart.md](quickstart.md) V1）

### Tests for User Story 1 ⚠️

> NOTE: 先写测试并确认失败，再实现（针对纯逻辑）

- [X] T011 [P] [US1] Unit tests for InterceptionController (去抖窗口、抑制窗口过期判定、命中条件、userOpenedPkg 永久标记) in `app/src/test/kotlin/com/fivesec/app/interception/InterceptionControllerTest.kt`
- [X] T012 [P] [US1] Unit tests for BlockingViewModel state-machine invariants (倒计时未结束不可选且置灰；仅 CHOICE_UNLOCKED 后可 OPEN/CANCEL；OPEN 必触发抑制；按钮从置灰禁用到可用的状态转换) in `app/src/test/kotlin/com/fivesec/app/blocking/BlockingViewModelTest.kt`
- [X] T013 [P] [US1] Compose UI test for BlockingScreen button state changes (置灰且禁用→正常可用 + countdown + userOpenedPkg protection) in `app/src/androidTest/kotlin/com/fivesec/app/blocking/BlockingScreenTest.kt`

### Implementation for User Story 1

- [X] T014 [P] [US1] Accessibility service config: `app/src/main/res/xml/accessibility_service_config.xml`（`typeWindowStateChanged`, 仅必要 capability, package filter 机制）并在 manifest 绑定
- [X] T015 [P] [US1] BlockingViewModel countdown state machine: `blocking/BlockingViewModel.kt`（状态 BLOCKING→CHOICE_UNLOCKED→OPENED/CANCELED/INTERRUPTED，5→0 倒计时，按钮从置灰且禁用到正常可用）
- [X] T016 [US1] AppBlockerAccessibilityService: `interception/AppBlockerAccessibilityService.kt`（`onAccessibilityEvent` 处理 `TYPE_WINDOW_STATE_CHANGED`，匹配目标包名 → 委托 InterceptionController → `performGlobalAction(GLOBAL_ACTION_HOME)` 后启动 BlockingActivity）
- [X] T017 [US1] InterceptionController: `interception/InterceptionController.kt`（去抖 + 进程内抑制 Map<包名,过期> + 决策：读全局开关与 `TargetApp.isEnabled` → `FLAG_ACTIVITY_NEW_TASK` 启动 BlockingActivity，传入包名）
- [X] T018 [US1] BlockingScreen Compose UI: `blocking/ui/BlockingScreen.kt`（"是否打开 {应用名}？"标题 + 提肛引导文案 + 大号 5→0 倒计时 + 按钮置灰且禁用状态到正常可用的状态转换）
- [X] T019 [US1] BlockingActivity: `blocking/BlockingActivity.kt`（托管 Compose + 绑定 ViewModel；打开→写抑制窗口+记 OPENED+`getLaunchIntentForPackage` 重启目标；取消→记 CANCELED+finish 留桌面；Home/息屏→记 INTERRUPTED）
- [X] T020 [US1] First-run permission onboarding: `settings/ui/OnboardingScreen.kt` + `util/AccessibilityPermissionHelper.kt`（检测服务是否启用、引导跳转系统无障碍设置、Android 13+ Restricted Settings 提示、诚实说明仅读包名不读内容）

**Checkpoint**: US1 独立可用——点击默认清单内应用可触发完整拦截流程

---

## Phase 4: User Story 2 - 自行配置被拦截的应用列表 (Priority: P2)

**Goal**: 用户可添加/移除被拦截应用、全局开关与单应用开关（[spec.md](spec.md) US2）

**Independent Test**: 添加微博 → 打开微博被拦截；移除微博 → 不再拦截；单独关 B站 → B站放行其余照常；全局关 → 全不拦截（[quickstart.md](quickstart.md) V2）

### Tests for User Story 2

- [X] T021 [P] [US2] Tests for TargetAppRepository/AppListViewModel (添加/移除/开关切换 + 已安装应用解析 + 3-app limit validation + appName display logic) in `app/src/test/kotlin/com/fivesec/app/settings/AppListViewModelTest.kt`

### Implementation for User Story 2

- [X] T022 [P] [US2] SettingsViewModel: `settings/viewmodels/SettingsViewModel.kt`（全局总开关 Flow + 切换，绑定 SettingsDataStore）
- [X] T023 [P] [US2] AppListViewModel: `settings/viewmodels/AppListViewModel.kt`（目标清单 Flow、添加/移除/单应用开关、经 `PackageManager` 列出已安装应用以供选择、3-app limit 验证逻辑）
- [X] T024 [US2] AppListScreen Compose: `settings/ui/AppListScreen.kt`（目标应用列表显示友好名称 + 可展开查看包名 + 每项启用开关 + 移除；"添加应用"选择器 + 达到3个限制时的提示）
- [X] T025 [US2] SettingsScreen Compose: `settings/ui/SettingsScreen.kt`（全局开/关总开关、入口到应用清单/统计、无障碍权限状态显示）
- [X] T026 [US2] MainActivity + NavHost: `MainActivity.kt`（路由 onboarding/settings/appList/stats；`@AndroidEntryPoint`）

**Checkpoint**: US1 + US2 均可独立工作

---

## Phase 5: User Story 3 - 查看每日锻炼与拦截情况 (Priority: P3)

**Goal**: 统计今日拦截次数、取消/打开分布、连续完成锻炼天数，以及应用级别的核心三指标统计（[spec.md](spec.md) US3）

**Independent Test**: 触发多次拦截混合选择 → 统计页正确显示今日次数、取消/打开分布与 streak；应用级别显示拦截总数、取消率、完成锻炼次数（[quickstart.md](quickstart.md) V3）

### Tests for User Story 3

- [X] T027 [P] [US3] Tests for streak & aggregation logic (今日计数、按 outcome 分布、连续天数计算、应用级别聚合) in `app/src/test/kotlin/com/fivesec/app/settings/StatsViewModelTest.kt`

### Implementation for User Story 3

- [X] T028 [P] [US3] Aggregation queries: 完善 `data/db/InterceptionEventDao.kt`（今日总数、按 OPENED/CANCELED 计数、按天聚合用于 streak、按应用聚合用于 AppStatistics）
- [X] T029 [P] [US3] StatsViewModel: `settings/viewmodels/StatsViewModel.kt`（今日拦截/取消/打开 + 连续完成锻炼天数 + 应用级别核心三指标）
- [X] T030 [US3] StatsScreen Compose: `settings/ui/StatsScreen.kt`（今日拦截次数、取消 vs 打开分布、连续天数；应用级别统计显示每个目标的拦截总数、取消率、完成锻炼次数；空态文案）

**Checkpoint**: 全部用户故事均可独立运行

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 跨故事的健壮性、边界与验证（对应 [spec.md](spec.md) Edge Cases 与 [contracts/permissions-setup.md](contracts/permissions-setup.md) 风险）

- [X] T031 [P] Verify deep-link/notification-triggered opens are intercepted (默认一并拦截)；复核"取消"落地为桌面与"打开"重拉目标，无残留前台
- [X] T032 [P] Interrupted-countdown & repeated-tap review: `blocking/BlockingActivity.kt`/`BlockingViewModel.kt` 处理 Home/息屏 → INTERRUPTED；防止重复拉起与二次拦截（依赖 T017 抑制窗口）
- [X] T033 [P] Battery optimization: 收紧 `app/src/main/res/xml/accessibility_service_config.xml`（仅 `typeWindowStateChanged` + 目标包名过滤）；新增 OEM 自启动/省电白名单引导页 `settings/ui/BatteryWhitelistScreen.kt`
- [X] T034 [P] Platform-risk detection: 检测 AccessibilityService 反复被禁用/Android 17 Advanced Protection 特征，在 `settings/ui/SettingsScreen.kt` 给出明确说明文案（[research.md](research.md) R6）
- [X] T035 [P] Error/empty states: 目标应用被卸载时在清单中标记/禁用（`TargetAppRepository`）；统计空态；权限丢失时的降级提示
- [ ] T036 Run [quickstart.md](quickstart.md) end-to-end validation (V1–V4): `./gradlew :app:installDebug`, `:app:testDebugUnitTest`, `:app:connectedDebugAndroidTest` *(未运行：本机无 Android SDK，需在 Android Studio 中执行)*
- [X] T037 [P] Documentation: `README.md`（权限用途说明、侧载分发与构建步骤、Android 17/Restricted Settings 限制说明、3-app limit 说明、应用级别统计功能说明）

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 无依赖，立即开始
- **Foundational (Phase 2)**: 依赖 Phase 1；**阻塞所有用户故事**
- **User Stories (Phase 3–5)**: 均依赖 Phase 2 完成；可并行（多人）或按优先级 P1→P2→P3 串行
- **Polish (Phase 6)**: 依赖所涉及用户故事完成

### User Story Dependencies

- **US1 (P1)**: Phase 2 完成后即可开始；不依赖其它故事（默认清单 T009 保证无需 US2 配置即可拦截）
- **US2 (P2)**: Phase 2 完成后即可开始；与 US1 独立可测（共享 TargetAppRepository）
- **US3 (P3)**: Phase 2 完成后即可开始；依赖 InterceptionEvent 数据（US1 运行后才有真实事件，但可注入测试数据独立验证）

### Within Each User Story

- 测试先写并失败 → 再实现
- 纯逻辑（ViewModel/Controller）→ 服务/仓储 → UI/Activity → 集成
- 完成后再进入下一优先级

### Parallel Opportunities

- Phase 1: T003、T004 与 T001/T002 独立可并行
- Phase 2: T006、T008、T009 互相独立可并行
- US1: T011、T012、T013 测试并行；T014、T015 并行
- US2: T022、T023 并行
- US3: T028、T029 并行
- Phase 6: T031–T035、T037 多为不同文件，可并行

---

## Parallel Example: User Story 1

```bash
# 并行编写 US1 测试（不同文件）：
Task: "Unit tests for InterceptionController ... InterceptionControllerTest.kt"
Task: "Unit tests for BlockingViewModel ... BlockingViewModelTest.kt"
Task: "Compose UI test for BlockingScreen ... BlockingScreenTest.kt"

# 并行编写 US1 无依赖实现（不同文件）：
Task: "accessibility_service_config.xml ..."
Task: "BlockingViewModel countdown state machine ..."
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. 完成 Phase 1: Setup
2. 完成 Phase 2: Foundational（**关键——阻塞所有故事**，含默认清单种子 T009）
3. 完成 Phase 3: User Story 1
4. **STOP & VALIDATE**: 按 [quickstart.md](quickstart.md) V1 独立验证（点击抖音→拦截页→5s→打开/取消）
5. 可演示/分发（侧载）

### Incremental Delivery

1. Setup + Foundational → 基础就绪
2. +US1 → 独立测试 → 演示（**MVP**）
3. +US2 → 独立测试 → 演示
4. +US3 → 独立测试 → 演示
5. Polish → 端到端验证（V1–V4）→ 发布

### Parallel Team Strategy

1. 团队共完成 Setup + Foundational
2. Foundational 完成后：开发者 A→US1、B→US2、C→US3
3. 各故事独立集成

---

## Notes

- [P] 任务 = 不同文件、无依赖
- [Story] 标签将任务映射到具体用户故事以可追溯
- 每个用户故事应可独立完成与测试
- 实现前确认测试失败
- 每个任务或逻辑分组后提交
- 可在任意 Checkpoint 停下独立验证故事
- 避免：模糊任务、同文件冲突、破坏独立性的跨故事依赖

---

## Recent Updates (2026-07-28)

基于 `/speckit-clarify` 会话的澄清结果，已更新以下任务：

### UI 行为澄清 (FR-006)
- **按钮状态**: 从"锁定"更新为"置灰且禁用"，更明确的视觉传达
- **影响任务**: T012, T013, T015, T018 - 涉及倒计时期间按钮状态

### 应用数量限制 (FR-007)  
- **3个应用限制**: 添加业务层验证逻辑和UI提示
- **影响任务**: T007, T009, T010, T023, T024 - 涉及应用管理

### 应用名称显示 (FR-008)
- **友好名称显示**: 显示应用名称（如"小红书"）而非包名，可展开查看包名
- **影响任务**: T009, T024 - 涉及应用列表显示

### 应用级别统计 (FR-012)
- **核心三指标**: 每个应用显示拦截总数、取消率、完成锻炼次数
- **影响任务**: T006, T007, T010, T027, T028, T029, T030 - 涉及数据模型和统计功能

这些更新确保实现与澄清后的规范完全一致，提供更好的用户体验和更清晰的产品边界。
