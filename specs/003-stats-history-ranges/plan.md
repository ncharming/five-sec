# Implementation Plan: 各应用历史数据（日/周/月/年）+ 统计模块去冗余

**Branch**: `003-stats-history-ranges` | **Date**: 2026-09-09 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-stats-history-ranges/spec.md`

## Summary

在 001/002 既有能力上重构统计页，**不改顶部今日四卡、拦截机制与清单逻辑**：

1. **各应用历史数据**：现有 `observeTodayCountsByPackage(startOfDay)` 本质是 `WHERE timestamp >= :start GROUP BY packageName` 的聚合——泛化为"任意自然周期起点"即可支持 日/周/月/年 四档，**事件表零 schema 变更**。周期起点在 Kotlin 侧用 `java.time` 计算（本地时区：今天零点 / 本周一零点 / 本月 1 号零点 / 本年 1 月 1 日零点）传入 SQL。UI 在应用卡片区上方加一行 Material3 `SegmentedButton`，默认"日"档，首屏与现状一致。
2. **数据留存保障（审计结论）**：`interception_events` 只增不删（全库无 DELETE 路径）、Room 无 `fallbackToDestructiveMigration`——"自安装起全量留存"现状已满足，本特性把它固化为红线约束与回归测试，不为存储做任何改造。
3. **去冗余**：删除 `AppStatistics` 冗余汇总路径（表 + 实体 + DAO + 每次拦截的双写 + ViewModel 死代码），数据库 v2→v3 迁移 `DROP TABLE app_statistics`，事件流水结构不变。

技术决策与权衡见 [research.md](research.md)，实体与迁移见 [data-model.md](data-model.md)，接口与 UI 契约见 [contracts/](contracts/)。

## Technical Context

**Language/Version**: Kotlin 2.0.21（既有）

**Primary Dependencies**（全部既有，零新增）：
- Jetpack Compose + Material 3（`SegmentedButton` 在 M3 1.1+ 可用，当前 BOM 2024.12.01 满足）
- Room 2.6.1（查询改造 + v3 迁移）
- Hilt、Kotlin Coroutines / Flow（结构不变）

**Storage**: 复用 `five_sec.db`。事件表 `interception_events` 零变更；删除 `app_statistics` 表（`MIGRATION_2_3`）。**必须走正式迁移，禁止破坏性回退**（数据留存红线）。

**Testing**: JUnit + Robolectric + `kotlinx-coroutines-test`（沿用既有栈）：
- `DateUtil` 周期边界单测（周一/月初/年初/周日/跨年/时区参数）
- DAO 范围聚合测试（沿用 `InterceptionEventDaoTest` 的 in-memory Room 模式，插入跨周期事件核对四档计数）
- ViewModel 档位切换测试（沿用 `StatsViewModelTest` 模式）
- 顶部四卡回归（既有 `StatsViewModelTest` 断言不动）

**Target Platform**: Android，`minSdk 26` / `targetSdk 35`（`java.time` 全区间可用）

**Project Type**: mobile-app（单一 `app/` 模块）

**Performance Goals**:
- 档位切换数据呈现 < 1s（本地 SQLite 聚合 + Flow，个人级数据量毫秒级，目标天然满足）
- 品牌色提取沿用既有后台缓存机制，零新增开销

**Constraints**:
- 纯离线单用户（既有）
- 页面停留跨周期不自动刷新（与现状一致）
- 统计单一数据源：事件流水实时聚合，禁止再引入预聚合/双写

**Scale/Scope**: 改动约 9 个文件 + 3 个测试文件；`StatsScreen` / `StatsViewModel` / `InterceptionEventDao` / `InterceptionRepository` / `DateUtil` / `AppDatabase` / `strings.xml`，删除 `AppStatistics` / `AppStatisticsDao` / `AppModule` 相应 Provider，及 `StatsViewModel` 死代码路径。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**状态**：`.specify/memory/constitution.md` 为未填充占位模板，无具体门禁可执行 → **GATE: PASS（无违规）**（与 001/002 plan 口径一致）。

**Phase 1 设计后复检**：设计遵循最小改动——事件表零 schema 变更、零新增依赖、零新增模块；唯一结构性变更是用户明确要求的冗余表删除，且通过正式迁移安全执行。Complexity Tracking 表留空。

## Project Structure

### Documentation (this feature)

```text
specs/003-stats-history-ranges/
├── plan.md              # 本文件
├── research.md          # Phase 0：技术决策与权衡
├── data-model.md        # Phase 1：实体、迁移与投影
├── quickstart.md        # Phase 1：端到端验证指南
├── contracts/
│   ├── stats-range-query.md   # 查询层契约（DateUtil/DAO/Repository）
│   └── stats-range-ui.md     # 统计页 UI 契约（档位切换与卡片）
└── tasks.md             # /speckit-tasks 产出
```

### Source Code (repository root)

```text
app/src/main/kotlin/com/fivesec/app/
├── data/
│   ├── db/                  # InterceptionEventDao（查询泛化）、AppDatabase（v3 迁移）、删 AppStatisticsDao
│   └── repository/          # InterceptionRepository（透传范围查询、删双写）
├── settings/
│   ├── ui/StatsScreen.kt    # 档位切换 SegmentedButton + 卡片文案
│   └── viewmodels/StatsViewModel.kt  # StatsRange 状态流 + appRangeStats；删死代码
├── domain/model/            # 删 AppStatistics.kt
├── di/AppModule.kt          # 删 AppStatisticsDao Provider
└── util/DateUtil.kt         # startOfWeek/Month/YearMillis

app/src/main/res/values/strings.xml   # 档位与通用指标文案

app/src/test/kotlin/com/fivesec/app/
├── data/db/InterceptionEventDaoTest.kt      # 四档范围聚合用例
├── settings/StatsViewModelTest.kt           # 档位切换 + 顶部回归
└── util/DateUtilTest.kt                     # 周期边界用例（新增）
```

**Structure Decision**: 单模块移动应用；查询层 → ViewModel → UI 的既有三层不动，仅泛化参数与新增档位状态。
