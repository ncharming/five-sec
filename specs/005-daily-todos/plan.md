# Implementation Plan: 每日待办与拦截流程打通（待办模块 + 拦截页待办卡片 + 信息架构重构 + 提示语循环）

**Branch**: `005-daily-todos`（按仓库惯例直接在 `master` 实施） | **Date**: 2026-09-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-daily-todos/spec.md`

## Summary

在既有拦截链路上新增待办模块并完成四项打通/重构，**不改拦截触发、5 秒状态机、清单上限与统计口径**：

1. **待办存储（Room v5）**：新增 `todos` 表（`id` 自增 PK / `text` / `isEnabled` / `lastCompletedDate`）。"每日重置"为惰性求值：完成判定 = `lastCompletedDate == 今天`，无清理任务、无 join。同一迁移把存量 `hints.kind='stack'` 行改挂 `'pool'`（拦截页输入退役后的零丢失处置）。
2. **同步快照（对齐 hints 模式）**：`TodoRepository` 后台收集 DAO Flow 到内存快照；覆盖层创建时主线程同步 `todayTodos(today)` 取启用条目 + 完成态；`BlockingOverlay` 以构造参数接收 `List<TodayTodo>`（保持手动构造 ViewModel 形态）。
3. **待办页（新「待办」Tab）**：`TodoViewModel`（注入 `TimeProvider`，`today` 可刷新应对跨日）+ `TodoScreen`（增删改/启停/勾选/上限 20 名额行/空态引导，弹窗走 `FiveSecDialog` 外壳）。
4. **信息架构重构**：`HomeTab` 改为 [TODOS, INTERCEPT, TIPS, STATS]，默认 TODOS；新建 `InterceptScreen` 承载"总开关卡（含无障碍状态行）+ 应用清单"合并布局；`SettingsScreen`/`AppListScreen` 退役删除（`SettingsViewModel`/`AppListViewModel` 保留并挂同一页）。
5. **提示语循环**：`HintRepository` 删除全部栈机制；`takeNextHint` 改为对 `内置 + 池` 单一序列按持久化游标循环取值；游标经 `HintCursorStore`（DataStore 新键）持久化，写路径用 StateFlow 收敛防乱序。

技术决策与权衡见 [research.md](research.md)，表结构与迁移见 [data-model.md](data-model.md)，接口与 UI 契约见 [contracts/](contracts/)。

## Technical Context

**Language/Version**: Kotlin 2.0.21（既有）

**Primary Dependencies**（全部既有，零新增）：
- Room 2.6.1（新表 + v5 迁移 + Flow 观察）
- DataStore Preferences 1.1.1（循环游标持久化）
- Hilt（Repository @Singleton；服务侧经既有 EntryPoint 暴露 TodoRepository）
- Kotlin Coroutines / Flow（快照收集 + 游标写收敛）
- 传统 View widget（覆盖层待办卡片为 TextView/LinearLayout，不引入 Compose 于覆盖层）
- material-icons-extended（待办 Tab Checklist 图标，已在依赖内）

**Storage**: 复用 `five_sec.db`（v4 → v5 正式迁移：`CREATE TABLE todos` + `UPDATE hints SET kind='pool' WHERE kind='stack'`）与 `five_sec_settings` DataStore（新键 `hint_cycle_cursor`）。**禁止破坏性回退**（既有红线）。

**Testing**: JUnit + Robolectric + `kotlinx-coroutines-test`（沿用既有栈）：
- `TodoRepositoryTest`：手写 fake DAO 纯 JVM——快照过滤/完成态日期映射、add 校验与上限、setCompleted 日期写入与清空、rename/remove/setEnabled 转发
- `TodoViewModelTest`：今日勾选转发（含 today 口径）、add 截断/空白、refreshToday 跨日
- `HintRepositoryTest`：重写为循环语义——序列顺序、回环、游标跨实例续接、池增删取模、add 校验
- `HintDaoTest`：去栈化（kind 只剩 pool 口径）
- `AppDatabaseMigrationTest`：新增 v4 → v5 用例（手建 v4 库含 stack 行 → v5 打开 → todos 可用 + stack 改挂 pool）
- `BlockingOverlay`/Compose UI 不做 UI 单测（项目现状），走 quickstart 手测

**Target Platform**: Android，`minSdk 26` / `targetSdk 35`

**Performance Goals**:
- `todayTodos` / `takeNextHint` 主线程同步纯内存操作（锁内列表映射，微秒级），零 IO 阻塞
- 游标写路径经 StateFlow 收敛为"最后一次写"，拦截风暴下不堆积 IO

**Constraints**:
- 覆盖层创建路径（`onAccessibilityEvent` 主线程）不得引入挂起/IO
- 隐私红线：待办仅本机存储，不联网、无通知（FR-014）
- 既有拦截/统计/清单行为零回归（FR-013）；`interception_events` 零触碰

**Scale/Scope**: 新增 8 个主代码文件 + 3 个测试文件；修改 8 个主代码文件；删除 2 个 UI 文件（内容并入合并页）。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**状态**：`.specify/memory/constitution.md` 为未填充占位模板，无具体门禁可执行 → **GATE: PASS（无违规）**（与 001/003/004 plan 口径一致）。

**Phase 1 设计后复检**：单表新增 + 一条 UPDATE 迁移；零新增依赖、零新增模块、零新增权限；覆盖层仅插入一块 TextView 区并删除输入行，状态机与按钮锁定逻辑不动。Complexity Tracking 表留空。

## Project Structure

### Documentation (this feature)

```text
specs/005-daily-todos/
├── plan.md                        # 本文件
├── research.md                    # Phase 0：技术决策与权衡
├── data-model.md                  # Phase 1：todos 表、迁移与值类型
├── quickstart.md                  # Phase 1：端到端验证指南
├── contracts/
│   ├── todo-repository.md         # 待办存储与快照契约
│   ├── todo-ui.md                 # 待办页 + 覆盖层待办卡片契约
│   └── hint-cycle.md              # 提示语循环与游标契约
├── checklists/requirements.md     # 规格质量自检
└── tasks.md                       # 实施任务清单（随实现逐项勾选）
```

### Source Code (repository root)

```text
app/src/main/kotlin/com/fivesec/app/
├── blocking/BlockingOverlay.kt              # 删输入链路；构造参数 +todos；紧凑卡片
├── interception/AppBlockerAccessibilityService.kt  # EntryPoint +todoRepository；接线快照
├── data/
│   ├── db/AppDatabase.kt                    # v5 + MIGRATION_4_5 + todoDao()
│   ├── db/TodoDao.kt                        # 新增
│   ├── datastore/SettingsDataStore.kt       # +hintCursor 流与写入
│   ├── datastore/DataStoreHintCursorStore.kt# 新增：HintCursorStore 实现
│   └── repository/
│       ├── TodoRepository.kt                # 新增：快照 + todayTodos + 增删改
│       └── HintRepository.kt                # 重写：栈删除 + 循环游标
├── domain/model/Todo.kt                     # 新增：实体 + TodayTodo
├── settings/
│   ├── ui/TodoScreen.kt                     # 新增：待办页
│   ├── ui/InterceptScreen.kt                # 新增：总开关卡 + 应用清单合并页
│   ├── ui/SettingsScreen.kt                 # 删除（并入 InterceptScreen）
│   ├── ui/AppListScreen.kt                  # 删除（并入 InterceptScreen）
│   └── viewmodels/TodoViewModel.kt          # 新增
├── di/AppModule.kt                          # 迁移注册 + provideTodoDao + HintCursorStore 绑定
└── HomeScreen.kt（根包）                     # Tab 枚举重构 + 默认待办

app/src/main/res/values/strings.xml          # 待办页/覆盖层卡片/Tab 文案；删提示语输入文案；提示语循环口径

app/src/test/kotlin/com/fivesec/app/
├── data/db/AppDatabaseMigrationTest.kt      # +v4→v5 用例；v3→v4 用例去栈化
├── data/db/HintDaoTest.kt                   # 去栈化
├── data/repository/HintRepositoryTest.kt    # 重写为循环语义
├── data/repository/TodoRepositoryTest.kt    # 新增
└── settings/TodoViewModelTest.kt            # 新增
```

**Structure Decision**: 沿用"数据层（DAO→Repository）→ 服务/VM → UI"三层结构；`TodoRepository` 与 `HintRepository` 同构（快照 + 主线程同步读），`InterceptScreen` 是唯一的配置聚合点，`TodoScreen` 是唯一的待办写入口。
