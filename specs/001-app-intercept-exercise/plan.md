# Implementation Plan: App Launch Interceptor with 5-Second Exercise Prompt (五秒 / five-sec)

**Branch**: `001-app-intercept-exercise` | **Date**: 2026-07-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/001-app-intercept-exercise/spec.md`

## Summary

一个原生 Android 应用：当用户尝试打开被列入"目标应用清单"的沉浸式内容应用（默认抖音、小红书、B站）时，应用通过 **AccessibilityService** 实时检测到该应用进入前台，立刻返回桌面并弹出全屏拦截页 `BlockingActivity`，强制引导一次 5 秒提肛（凯格尔）锻炼；5 秒倒计时结束前"打开/取消"按钮锁定，结束后用户才可选择——选"打开"则在短暂抑制窗口内重新启动目标应用，选"取消"则留在桌面。所有数据本地存储（Room + DataStore），无需账号与联网。技术方案与权衡详见 [research.md](research.md)。

## Technical Context

**Language/Version**: Kotlin 2.x（新 Android 项目的现代默认；Jetpack Compose 与协程一等公民）

**Primary Dependencies**:
- Jetpack Compose + Material 3（UI）
- AndroidX AccessibilityService（应用启动检测 + 覆盖层核心能力）
- Room（结构化本地数据）+ DataStore Preferences（键值设置）
- Hilt（依赖注入）、Kotlin Coroutines / Flow（异步与响应式）
- Navigation-Compose（设置页路由）

**Storage**: 本地设备存储。Room (SQLite) 存放拦截事件日志与目标应用清单；DataStore (Preferences) 存放全局/单应用开关等简单设置。无后端、无云同步。

**Testing**:
- 单元测试：JUnit + Robolectric（含 Android 依赖的纯逻辑，如倒计时状态机、抑制窗口判定）
- UI 测试：Compose UI Test（`createAndroidComposeRule`，验证拦截页与设置页）
- 跨应用流程：UIAutomator（端到端验证"点击目标应用 → 拦截页 → 打开/取消"）

**Target Platform**: Android，`minSdk 26`（Android 8.0，覆盖 98%+ 设备，满足 Compose 与覆盖层 API），`targetSdk 35`（Android 15）

**Project Type**: mobile-app（单一 Gradle 应用模块 `app/`；MVP 不拆多模块）

**Performance Goals**:
- 点击目标应用图标 → 拦截页可见可读：p90 < 1 秒（对应 SC-002）
- AccessibilityService 单次事件处理 < 16ms，不阻塞主线程、不产生卡顿
- 5 秒倒计时显示流畅（60fps），可见且可数的 5→0 倒计时（SC-003）
- 后台/拦截机制日常使用无可感知额外耗电（SC-007）

**Constraints**:
- 纯离线、无账号、无网络权限（核心功能）
- AccessibilityService 必须窄范围配置（仅监听 `typeWindowStateChanged` + 目标包名），降低耗电与隐私面
- 需处理 Android 13+ "Restricted Settings"（引导用户在系统设置中放行）
- **风险**：Android 17 "Advanced Protection Mode" 可能完全阻止非无障碍类应用使用 AccessibilityService；需检测并向用户说明限制（见 [research.md](research.md)）
- 国产 ROM（小米/华为/OPPO/vivo）激进的后台清理策略：需提供"自启动/省电白名单"引导
- 不拦截系统必备应用（电话、短信等）；仅拦截用户安装的第三方应用

**Scale/Scope**: 单用户、单设备；1 个拦截页 + 3 个设置相关页（应用清单 / 统计 / 设置）；默认种子清单 3 个应用；MVP 锻炼仅"5 秒提肛"。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**状态**：`.specify/memory/constitution.md` 当前仍为未填充的占位模板，未定义任何具体核心原则（如 Test-First、Library-First 等），因此**无具体门禁可执行 → GATE: PASS（无违规）**。

**建议**：在进入实现前运行 `/speckit-constitution` 为本项目定义核心原则（例如：可测试性优先、最小权限、离线优先、零网络权限等），之后所有 plan/任务将据此校验。

**Phase 1 设计后复检**：本计划的设计未引入与（未来）合理原则相冲突的复杂度（单模块、单一应用、无后端），无复杂度违规需登记。Complexity Tracking 表留空。

## Project Structure

### Documentation (this feature)

```text
specs/001-app-intercept-exercise/
├── plan.md              # 本文件（/speckit-plan 输出）
├── research.md          # Phase 0 输出：技术决策与权衡
├── data-model.md        # Phase 1 输出：实体与拦截状态机
├── quickstart.md        # Phase 1 输出：端到端验证指南
├── contracts/           # Phase 1 输出：行为/UI 契约
│   ├── interception-flow.md
│   └── permissions-setup.md
└── tasks.md             # Phase 2 输出（/speckit-tasks 生成，本命令不创建）
```

### Source Code (repository root)

```text
五秒 / five-sec (单 Android 应用模块)
├── settings.gradle.kts
├── build.gradle.kts                # 根构建脚本
├── gradle/libs.versions.toml       # 版本目录（依赖集中管理）
└── app/
    ├── build.gradle.kts            # 应用模块构建脚本（Compose、Room、Hilt 等）
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── kotlin/com/fivesec/app/
        │   │   ├── FiveSecApp.kt                  # Application，@HiltAndroidApp
        │   │   ├── MainActivity.kt                # 设置 UI 宿主（Compose NavHost）
        │   │   ├── di/                            # Hilt 模块
        │   │   ├── data/
        │   │   │   ├── db/                         # Room：AppDatabase、TargetAppDao、InterceptionEventDao
        │   │   │   ├── datastore/                  # SettingsDataStore（全局/单应用开关）
        │   │   │   ├── repository/                 # TargetAppRepository、InterceptionRepository
        │   │   │   └── seed/                       # DefaultAppSeed（抖音/小红书/B站 默认清单）
        │   │   ├── interception/
        │   │   │   ├── AppBlockerAccessibilityService.kt   # 检测目标应用进入前台
        │   │   │   ├── InterceptionController.kt           # 判定 + 去抖 + 抑制窗口（防重入）
        │   │   │   └── res/xml/accessibility_service_config.xml
        │   │   ├── blocking/
        │   │   │   ├── BlockingActivity.kt         # 5 秒提肛 + 打开/取消 拦截页
        │   │   │   ├── BlockingViewModel.kt        # 倒计时状态机
        │   │   │   └── ui/BlockingScreen.kt        # Compose 拦截 UI
        │   │   ├── settings/
        │   │   │   ├── ui/                          # AppListScreen、StatsScreen、SettingsScreen（Compose）
        │   │   │   └── viewmodels/
        │   │   └── domain/
        │   │       ├── model/                       # TargetApp、InterceptionEvent、Exercise
        │   │       └── usecase/                     # 如 RecordInterception、ResolveTarget
        │   └── res/                                 # themes、strings、drawable、xml 配置
        ├── test/                                    # JVM 单元测试（Robolectric）
        └── androidTest/                             # 仪器化测试（Compose UI + UIAutomator）
```

**Structure Decision**: 采用 **Option：单应用模块（mobile-app）**。功能内聚在单一 `app/` 模块内，按职责分包（`interception` / `blocking` / `settings` / `data` / `domain`），符合本项目"单用户、单平台、无后端、MVP 三个页面"的规模，避免过早多模块化。未来若拆分（如将"拦截引擎"独立为 library），可在后续 spec 中通过 constitution 的 Library-First 原则触发。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

无违规，留空。
