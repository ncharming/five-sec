# Implementation Plan: 重复待办（按星期几 / 每 N 天间隔）

**Branch**: `006-recurring-todos`（按仓库惯例直接在 `master` 实施） | **Date**: 2026-09-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-recurring-todos/spec.md`

## Summary

给 specs/005 的每日待办增加"重复规则"，**不改完成判定、条目上限、拦截链路与统计口径**：

1. **规则建模（Room v5→v6）**：`todos` 新增 3 列——`repeatType`（0=每天 / 1=按星期几 / 2=每 N 天）、`repeatDays`（周几位掩码）、`intervalDays`（间隔 N），默认全 0 = 每天 → 老数据零感知；迁移为 3 条 `ALTER TABLE ADD COLUMN ... NOT NULL DEFAULT 0`，零丢失。**不新增锚点列**：滚动语义下"从未完成恒轮到"，间隔起算基准直接复用 `lastCompletedDate`（详见 research R2）。
2. **轮到判定（纯逻辑）**：`util/TodoRecurrence` 纯 JVM 函数 `isDue(...)`，惰性求值，进出均为 today 字符串口径（CI UTC 时区免疫）；周几 = DayOfWeek 位掩码命中；间隔 = 最近完成日为空恒到期，否则 `daysBetween(last, today) >= N`。
3. **快照过滤**：`TodoRepository.todayTodos(today)` 过滤条件扩为 `isEnabled && isDue` → 覆盖层卡片的 D/T 分母、未完成条目列表、"全完成 ✓ / 空块隐藏"自动获得"只看轮到"语义，**无障碍服务与 BlockingOverlay 零改动**。
4. **待办页**：不轮到的行灰显（Checkbox 禁用 + 弱化 + 「今天不用做」标注，条目不消失，全文弹窗/改名/删除/停用照旧）；编辑弹窗加规则区（三选一 + 周几七选 + N 天输入），周几全空禁止保存。
5. **数据写入**：repo `add(text, rule)`（默认每天，兼容既有调用）+ `setRecurrence(id, rule)` 定向 UPDATE（对齐 `setEnabled` 模式，不覆盖并发勾选）+ 兜底校验；VM 编辑确认时并行发 rename + setRecurrence。

技术决策与权衡见 [research.md](research.md)，表结构与迁移见 [data-model.md](data-model.md)，判定表与 UI 契约见 [contracts/](contracts/)，端到端手测见 [quickstart.md](quickstart.md)。

## Technical Context

**Language/Version**: Kotlin 2.0.21（既有）

**Primary Dependencies**（全部既有，零新增）：
- Room 2.6.1（v6 正式迁移 + Flow 观察）
- Hilt（注入关系不变，无新绑定）
- Kotlin Coroutines / Flow（快照收集 + VM 派生流）
- Compose Material 3（SegmentedButton / FilterChip 在既有 material3 依赖内）

**Storage**: 复用 `five_sec.db`，v5 → v6 正式迁移（3× `ALTER TABLE todos ADD COLUMN ... INTEGER NOT NULL DEFAULT 0`）。**禁止破坏性回退**（既有红线）；`interception_events` / `target_apps` / `hints` 零触碰。

**Testing**: JUnit4 + Robolectric + `kotlinx-coroutines-test`（沿用既有栈）：
- `TodoRecurrenceTest`（新，纯 JVM）：判定表全覆盖——每天恒真 / 周几命中与未命中 / 间隔从未完成·灰显期·第 N 天复活·到期未完成持续·时钟回拨 / N 边界
- `TodoRepositoryTest`：`todayTodos` 轮到过滤（周几未命中、间隔灰显期）、`setRecurrence` 校验与定向转发、`add` 带规则
- `TodoViewModelTest`：`dueToday` 派生、`refreshToday` 跨日重算、`setRecurrence` 转发
- `AppDatabaseMigrationTest`：+v5→v6 用例（手建 v5 库 → 全链迁移打开 → 行保留 + 新列默认 0）

**Target Platform**: Android，`minSdk 26` / `targetSdk 35`（java.time 原生可用，无脱糖需求）

**Performance Goals**:
- `todayTodos` 仍是主线程同步纯内存操作（每条多一次 O(1) 判定），零 IO 阻塞
- 轮到判定无状态、无缓存失效问题（惰性求值，与 005 每日重置同机制）

**Constraints**:
- 覆盖层创建路径（主线程）不得引入挂起/IO；不新增权限、无通知（FR-011）
- 既有拦截/统计/清单/待办行为零回归；统计页四卡与 streak 零触碰
- 提示语 30 字口径、待办 200 字口径（005 修订后）均不变

**Scale/Scope**: 新增 1 个主代码文件（TodoRecurrence）+ 1 个测试文件；修改 7 个主代码文件 + 3 个测试文件；无删除。

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**状态**：`.specify/memory/constitution.md` 为未填充占位模板，无具体门禁可执行 → **GATE: PASS（无违规）**（与 001–005 plan 口径一致）。

**Phase 1 设计后复检**：仅 3 列 ADD COLUMN 迁移；零新增依赖、零新增模块、零新增权限；覆盖层与拦截服务零改动；纯逻辑落在无 Android 依赖的 util。Complexity Tracking 表留空。

## Project Structure

### Documentation (this feature)

```text
specs/006-recurring-todos/
├── plan.md                        # 本文件
├── research.md                    # Phase 0：技术决策与权衡
├── data-model.md                  # Phase 1：todos 新列、迁移与不变式
├── quickstart.md                  # Phase 1：端到端验证指南
├── contracts/
│   ├── todo-recurrence.md         # 轮到判定纯函数契约（判定表）
│   ├── todo-repository.md         # DAO/Repository/迁移契约
│   └── todo-ui.md                 # 待办页规则编辑 + 灰显行 + 拦截卡片口径
├── checklists/requirements.md     # 规格质量自检
└── tasks.md                       # 实施任务清单（随实现逐项勾选）
```

### Source Code (repository root)

```text
app/src/main/kotlin/com/fivesec/app/
├── data/
│   ├── db/AppDatabase.kt                # version 6 + MIGRATION_5_6
│   ├── db/TodoDao.kt                    # +updateRecurrence 定向 UPDATE
│   └── repository/TodoRepository.kt     # todayTodos 轮到过滤 + add(text,rule) + setRecurrence
├── domain/model/Todo.kt                 # 实体 +3 列；TodayTodo 不变
├── settings/
│   ├── ui/TodoScreen.kt                 # 行灰显 + 编辑弹窗规则区
│   └── viewmodels/TodoViewModel.kt      # TodoRow +dueToday；add/setRecurrence 转发
├── util/TodoRecurrence.kt               # 新增：轮到判定纯逻辑
└── di/AppModule.kt                      # 注册 MIGRATION_5_6

app/src/main/res/values/strings.xml      # 规则标签/周几/今天不用做/至少选择一天

app/src/test/kotlin/com/fivesec/app/
├── util/TodoRecurrenceTest.kt           # 新增：判定表
├── data/db/AppDatabaseMigrationTest.kt  # +v5→v6 用例
├── data/repository/TodoRepositoryTest.kt# 轮到过滤 + setRecurrence
└── settings/TodoViewModelTest.kt        # dueToday 派生与转发
```

**Structure Decision**: 判定纯逻辑进 `util`（无 Android 依赖，VM/Repo/测试三方同源）；数据层沿用"定向 UPDATE + 内存快照"既有模式；UI 层在 005 骨架上做增量扩展，覆盖层保持零改动。

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

（无违规，留空）
