# Implementation Plan: 拦截页自定义提示语（栈式一次性提示 + 自定义提示语池）

**Branch**: `004-custom-hints` | **Date**: 2026-09-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-custom-hints/spec.md`

## Summary

在 001 既有拦截链路上扩展提示语来源与输入能力，**不改拦截触发、5 秒状态机、清单与统计**：

1. **提示语存储（Room v4）**：新增 `hints` 表（`id` 自增 PK / `text` / `kind`∈{stack, pool}）。栈序 = 自增 id 序（栈顶 = MAX(id)），"展示即出栈" = 展示时删除该行；池 = `kind='pool'` 行集合，与内置 7 条合并为随机池。零新增依赖（无 JSON 编解码需求，避开为 DataStore 引入序列化）。
2. **同步消费（快照模式，对齐 `InterceptionController`）**：`HintRepository` 后台收集 DAO Flow 到内存快照；覆盖层创建时在主线程**同步** `takeNextHint(builtin)`——内存 `synchronized` 弹栈顶 + 异步删库，栈空则 `(内置 + 池).random()`。已消费未落库的 id 用 pending 集合防"observe 重发导致栈顶复活"竞态。
3. **拦截页输入（传统 View 覆盖层内）**：`BlockingOverlay` 提示语下方新增 `[EditText(30字硬截断) | 保存]` 行 + 反馈 TextView；显式保存 → `pushStackHint`；输入框不受 5 秒按钮锁定影响；未保存草稿随覆盖层消失丢弃。
4. **设置页管理入口**：`SettingsScreen` 在"拦截应用清单"与"统计"之间插入"自定义提示语"；新路由 `HintListScreen`（查看/添加/删除，Compose + Hilt ViewModel）。

技术决策与权衡见 [research.md](research.md)，表结构与迁移见 [data-model.md](data-model.md)，接口与 UI 契约见 [contracts/](contracts/)。

## Technical Context

**Language/Version**: Kotlin 2.0.21（既有）

**Primary Dependencies**（全部既有，零新增）：
- Room 2.6.1（新表 + v4 迁移 + Flow 观察）
- Hilt（`HintRepository` @Singleton 注入；服务侧经既有 EntryPoint 暴露）
- Kotlin Coroutines / Flow（快照收集 + 异步持久化）
- 传统 View widget（覆盖层内 EditText/Button，不引入 Compose 于覆盖层）

**Storage**: 复用 `five_sec.db`，v3 → v4 正式迁移 `CREATE TABLE hints`。**禁止破坏性回退**（既有红线）。

**Testing**: JUnit + Robolectric + `kotlinx-coroutines-test`（沿用既有栈）：
- `HintDaoTest`：in-memory Room，插入/按 kind 观察（id 升序）/按 id 删除
- `HintRepositoryTest`：手写 fake DAO（StateFlow 驱动）纯 JVM 测试——LIFO 顺序、栈空回落合并池随机、已消费防复活、push/add/remove 持久化调用
- `HintListViewModelTest`：30 字截断、空白拒绝、remove 调用
- `AppDatabaseMigrationTest` 扩展：v3 → v4 既有数据保留、`hints` 表可用
- `BlockingOverlay` UI 为传统 View，沿用项目现状不做 UI 单测，走 quickstart 手测

**Target Platform**: Android，`minSdk 26` / `targetSdk 35`

**Project Type**: mobile-app（单一 `app/` 模块）

**Performance Goals**:
- `takeNextHint` 主线程同步纯内存操作（锁内两次列表操作，微秒级），零 IO 阻塞（FR-012）
- 表数据量个人级（数十行），无索引需求（YAGNI）

**Constraints**:
- 覆盖层创建路径（`onAccessibilityEvent` 主线程）不得引入挂起/IO
- 隐私红线：提示语仅本机存储，不联网（既有承诺）
- 既有拦截/统计/清单行为零回归（FR-011）

**Scale/Scope**: 新增 5 个主代码文件 + 3 个测试文件；修改 6 个主代码文件 + 1 个迁移测试 + strings.xml + README。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**状态**：`.specify/memory/constitution.md` 为未填充占位模板，无具体门禁可执行 → **GATE: PASS（无违规）**（与 001/003 plan 口径一致）。

**Phase 1 设计后复检**：最小改动——单表新增、零新增依赖、零新增模块；覆盖层仅插入一行输入控件，状态机与按钮锁定逻辑不动。Complexity Tracking 表留空。

## Project Structure

### Documentation (this feature)

```text
specs/004-custom-hints/
├── plan.md                       # 本文件
├── research.md                   # Phase 0：技术决策与权衡
├── data-model.md                 # Phase 1：hints 表、迁移与实体
├── quickstart.md                 # Phase 1：端到端验证指南
├── contracts/
│   ├── hint-repository.md        # 存储与消费层契约（DAO/Repository/竞态防护）
│   └── hint-ui.md                # 覆盖层输入 UI 与管理页契约
└── tasks.md                      # /speckit-tasks 产出
```

### Source Code (repository root)

```text
app/src/main/kotlin/com/fivesec/app/
├── blocking/BlockingOverlay.kt           # 提示语参数化 + 输入行 + 保存回调
├── interception/AppBlockerAccessibilityService.kt  # EntryPoint + takeNextHint 接线
├── data/
│   ├── db/AppDatabase.kt                 # v4 + MIGRATION_3_4 + hints()
│   ├── db/HintDao.kt                     # 新增
│   └── repository/HintRepository.kt      # 新增：快照 + take/push/池管理
├── domain/model/Hint.kt                  # 新增：Room 实体（id/text/kind）
├── settings/
│   ├── ui/SettingsScreen.kt              # "自定义提示语"入口
│   ├── ui/HintListScreen.kt              # 新增：管理页
│   └── viewmodels/HintListViewModel.kt   # 新增
├── di/AppModule.kt                       # provideHintDao
└── MainActivity.kt                       # Routes.HINTS 注册

app/src/main/res/values/strings.xml       # 输入框/保存/管理页文案

app/src/test/kotlin/com/fivesec/app/
├── data/db/HintDaoTest.kt                # 新增
├── data/db/AppDatabaseMigrationTest.kt   # 扩展 v3→v4 用例
├── data/repository/HintRepositoryTest.kt # 新增（fake DAO 纯 JVM）
└── settings/HintListViewModelTest.kt     # 新增
```

**Structure Decision**: 数据层（DAO→Repository）→ 服务/VM → UI 三层既有结构不动；`HintRepository` 同时服务无障碍服务（同步消费）与管理页（Flow 观察），是唯一的聚合点。
