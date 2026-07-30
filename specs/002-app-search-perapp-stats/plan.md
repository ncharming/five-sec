# Implementation Plan: 添加应用搜索 + 应用级今日统计（品牌色展示）

**Branch**: `002-app-search-perapp-stats` | **Date**: 2026-07-30 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/002-app-search-perapp-stats/spec.md`

## Summary

在已实现的 001 基础特性（五秒 / five-sec）上做两项增强，**不改拦截机制、3 个上限、权限流程与既有总体统计**：

1. **添加应用搜索**：当前 `AppListScreen` 的添加选择器是一个无搜索的 `AlertDialog` + `LazyColumn`，候选来自 `PackageUtil.installedUserApps(pm)`。本特性在选择器顶部加入搜索 `TextField`，按"友好名称（不分大小写）+ 包名"实时过滤；并把"枚举已安装应用"从主线程移到 IO 协程并缓存（这是 200+ 应用下的真实性能瓶颈，非过滤本身），满足 0.5s 过滤目标。
2. **应用级今日统计（品牌色）**：复用 `interception_events` 表，新增按 `packageName + startOfDay` 聚合的 DAO 查询（今日拦截总数、今日 `OPENED` 数），在 `StatsScreen` 为每个目标应用渲染一张卡片；卡片主题色取自该应用启动器图标的代表色，经 `androidx.palette` 提取并按包名缓存，失败/对比度不足时回退到品牌健康绿。

技术决策与权衡详见 [research.md](research.md)，实体与查询契约见 [data-model.md](data-model.md)。

## Technical Context

**Language/Version**: Kotlin 2.x（与 001 一致）

**Primary Dependencies**（既有，来自 `gradle/libs.versions.toml`）：
- Jetpack Compose + Material 3、Navigation-Compose（UI/路由）
- Room + DataStore Preferences（本地存储）
- Hilt（DI）、Kotlin Coroutines / Flow（异步与响应式）

**新增依赖**（本特性）：
- `androidx.palette:palette-ktx` —— 从应用图标 Bitmap 提取代表色（见 [research.md](research.md) R2）

**Storage**：复用 001 本地数据库。**不新增/不改任何 Room 实体与表结构**，无数据库迁移：今日应用级统计由 `interception_events` 表实时聚合得到，应用品牌色为运行时派生值（图标提取 + 内存缓存，不落库）。

**Testing**：
- 单元测试：JUnit + Robolectric + `kotlinx-coroutines-test`（沿用 001 既有测试栈）
  - 搜索过滤逻辑（名称/包名、大小写、空状态、去重）纯函数化，便于单测
  - 应用级今日统计的 Flow 聚合（仿 `StatsViewModelTest` 模式）
- 仪器化/UI 测试：Compose UI Test —— 验证搜索框交互、空状态、统计卡片渲染与品牌色着色

**Target Platform**: Android，`minSdk 26` / `targetSdk 35`（与 001 一致；Palette 在该区间全支持）

**Project Type**: mobile-app（单一 `app/` 模块，不新增模块）

**Performance Goals**:
- 添加应用搜索：输入后候选列表过滤刷新 p90 < 0.5s（对应 SC-001）
- 已安装应用枚举移出主线程：避免 200+ 应用下的 ANR/掉帧
- 品牌色提取：每个应用图标首次提取在 IO 线程一次性完成（< 50ms/图标），结果按包名缓存，后续 0 成本

**Constraints**:
- 纯离线、无账号、无网络（与 001 一致）
- 应用品牌色提取必须在非主线程执行（Palette/Bitmap 操作不可阻塞 UI）
- 统计卡片文字与品牌色背景须满足可访问性对比度（WCAG AA：正常文本 ≥ 4.5:1）

**Scale/Scope**: 单用户、单设备；改动 2 个既有页面（`AppListScreen` / `StatsScreen`）+ 各自 ViewModel + 1 个 DAO + 1 个 Repository + 1 个品牌色提取器；不新增屏幕（添加搜索复用既有选择器，统计卡片在既有 `StatsScreen` 内）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**状态**：`.specify/memory/constitution.md` 当前仍为未填充的占位模板，未定义任何具体核心原则，因此**无具体门禁可执行 → GATE: PASS（无违规）**（与 001 plan 一致）。

**建议**：进入实现前可运行 `/speckit-constitution` 为本项目定义核心原则（例如：可测试性优先、最小权限、离线优先、零网络权限、最小改动原则等）。

**Phase 1 设计后复检**：本计划的设计严格遵循"最小改动"——零数据库迁移、零新增模块、仅扩展既有文件——未引入任何与（未来）合理原则相冲突的复杂度，无复杂度违规需登记。Complexity Tracking 表留空。

## Project Structure

### Documentation (this feature)

```text
specs/002-app-search-perapp-stats/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出：技术决策与权衡
├── data-model.md        # Phase 1 输出：实体（复用）+ 新查询/派生视图
├── quickstart.md        # Phase 1 输出：端到端验证指南
├── contracts/           # Phase 1 输出：UI 契约
│   ├── add-app-search.md
│   └── perapp-stats-card.md
└── tasks.md             # Phase 2 输出（/speckit-tasks 生成，本命令不创建）
```

### Source Code (repository root)

```text
五秒 / five-sec（单 Android 应用模块，仅扩展既有文件，无新增模块）
app/
├── build.gradle.kts                 # [+1 依赖] 新增 androidx.palette:palette-ktx
└── src/main/kotlin/com/fivesec/app/
    ├── util/
    │   ├── PackageUtil.kt           # [改] installedUserApps 保持；新增"主线程友好"说明
    │   └── AppBrandColorExtractor.kt # [新增] 图标 → 代表色（Palette），@Inject @Singleton，按包名缓存
    ├── data/
    │   ├── db/
    │   │   └── InterceptionEventDao.kt   # [改] +2 查询：observeTodayCountByPackage /
    │   │                                  #        observeTodayCountByPackageAndOutcome
    │   └── repository/
    │       └── InterceptionRepository.kt # [改] +observeAppTodayStats(startOfDay)：Flow<List<AppTodayStats>>
    ├── di/
    │   └── AppModule.kt              # [改] 提供 AppBrandColorExtractor（如不以构造注入）
    └── settings/
        ├── viewmodels/
        │   ├── AppListViewModel.kt   # [改] installedApps 改为 IO 协程 + StateFlow 缓存；暴露过滤 API
        │   └── StatsViewModel.kt     # [改] +应用级今日统计 Flow（含品牌色）
        └── ui/
            ├── AppListScreen.kt      # [改] 选择器加搜索 TextField + 空状态 + 已添加置灰
            └── StatsScreen.kt        # [改] +每个目标应用的今日统计卡片（品牌色主题）
```

> 注：`interception` / `blocking` / `onboarding` / `data/seed` / `TargetAppRepository` 等模块本特性**不改动**。

**Structure Decision**: 沿用 001 的 **单应用模块（mobile-app）** 结构。本特性为纯增量增强，仅扩展既有页面与数据层文件、新增 1 个品牌色工具类，不引入新模块、新屏幕、新数据库表，符合最小改动与单模块规模。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，留空。
