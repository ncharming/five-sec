# Technical Plan: 提示语功能退役——待办成为拦截页唯一缓冲内容

**Feature Branch**: `009-retire-hints` | **Date**: 2026-09-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/009-retire-hints/spec.md`

## Summary

移除提示语这条内容链路（覆盖层提示语行、「提示语」Tab 与管理页、循环游标、内置开关、内置文案数组），`hints` 表物理保留但退出 Room 声明（v8→v9 空迁移）；今日待办卡片升格为覆盖层唯一缓冲内容，空态从"整卡隐藏"改为二分展示（无启用条目=引导添加、有启用但今日不轮到=告知），空态判定由 `TodoRepository` 快照一次加锁返回。

## Technical Context

- **Language/Version**: Kotlin 2.0.21（JVM 17）
- **Primary Dependencies**: Jetpack Compose + Material 3 · Hilt · Room · DataStore · Coroutines/Flow（零新增、零升级）
- **Storage**: Room `five_sec.db`（`hints` 表退役为"物理保留、不再声明"；DataStore `hint_cycle_cursor`/`builtin_hints_enabled` 残留不清理）
- **Testing**: JUnit4 + Robolectric（`:app:testDebugUnitTest`）；迁移测试沿用手工建库模式（`exportSchema=false`）
- **Target Platform**: Android 8.0+（minSdk 26）/ targetSdk 35，纯离线
- **Project Type**: 单 `:app` 模块 mobile-app
- **Performance Goals**: 不劣化——服务主线程取数从两处（hint+todo）减为一处
- **Constraints**: 无网络/无埋点；`interception_events`/`todo_completions` 零触碰；无破坏性迁移；统计页今日四数锚点不动
- **Scale/Scope**: 删 4 个主代码文件 + 3 个测试文件；动 1 个实体声明、1 个迁移、1 个 Tab 枚举、1 个覆盖层视图

## Constitution Check

*GATE: `.specify/memory/constitution.md` 为未填充占位模板（AGENTS.md 已注明，恒 PASS，不作真实门禁）。真实门禁按 AGENTS.md 安全红线对照：*

| 红线 | 本计划符合性 |
|---|---|
| 禁破坏性命令（DELETE/清库/`fallbackToDestructiveMigration`） | MIGRATION_8_9 为**空迁移**（零 SQL），`hints` 表与数据原样保留（FR-006） |
| `interception_events` 事件表零触碰 | 仅摘除 `Hint` 实体声明；两张事件流水表不在改动面（FR-009） |
| 数据只进不出（不联网/不埋点/不上传） | 纯删本地链路，无新增依赖 |
| 不擅自升级依赖 | `libs.versions.toml` 零变更 |

*Phase 1 设计后复查：data-model 与 contracts 均未引入上述红线的新触点，结论不变。*

## Goal & Non-Goals

**Goal**: 落地 spec FR-001~009——覆盖层无提示语、待办卡四态常驻、底部导航 3 Tab、存量数据零破坏。

**Non-Goals**:

- 不提供「提示语→待办」转化/导出（spec 假设：方案 B2 已否决）；
- 不物理删除 `hints` 表（是否 DROP 留待未来另决策，默认永久保留）；
- 不清理 DataStore 残留键（FR-007 允许残留）；
- 不动待办页/统计页交互与口径（007/008 契约不变；统计页今日四数锚点不动）；
- 不动拦截触发、5 秒状态机、打开/取消/打断时序（001 契约不变）；
- 不动 `todo_completions` 写入路径与任务统计。

## Architecture: 改动清单（单一事实源）

```
domain/model/Hint.kt                        删除（HintKind 一并）
domain/model/TodayTodo.kt                   +TodayTodosSnapshot(items, anyEnabled)
domain/model/AppSettings.kt                 删 builtinHintsEnabled 字段
data/db/HintDao.kt                          删除
data/db/AppDatabase.kt                      v9：entities 摘 Hint、删 hintDao()、+MIGRATION_8_9（空迁移）
data/repository/HintRepository.kt           删除（含 HintCursorStore 接口）
data/repository/TodoRepository.kt           todayTodos → TodayTodosSnapshot；KDoc 去 Hint 对齐表述
data/datastore/DataStoreHintCursorStore.kt  删除
data/datastore/SettingsDataStore.kt         删 BuiltinHintsSetting/hintCursor/两键
interception/AppBlockerAccessibilityService.kt  摘 hint 取数与 EntryPoint 成员
blocking/BlockingOverlay.kt                 删 hint 参数与 TextView；空态二分支；spacer 常驻
settings/ui/HomeScreen.kt                   HomeTab 摘 TIPS + 恢复兜底
settings/ui/HintListScreen.kt               删除
settings/viewmodels/HintListViewModel.kt    删除
settings/viewmodels/SettingsViewModel.kt    删 builtinHintsEnabled 默认值
settings/ui/Dialogs.kt                      注释引用清理（HintListScreen 字样）
di/AppModule.kt                             摘 provideHintDao/HintCursorStore/BuiltinHintsSetting
res/values/strings.xml                      删 hints_*/blocking_exercise_hints；+2 空态文案；共享键改名
test/…/data/db/HintDaoTest.kt               删除
test/…/settings/HintListViewModelTest.kt    删除
test/…/data/repository/HintRepositoryTest.kt 删除
test/…/data/repository/TodoRepositoryTest.kt 快照类型更新 + 空态二分用例
test/…/data/db/AppDatabaseMigrationTest.kt  +v8→v9 用例（hints 行留存断言）
```

## Key Decisions *(详见 [research.md](research.md))*

| # | 决策 | 备选与否决理由 |
|---|---|---|
| D1 | Room 摘除 `Hint` 实体 + v9 **空迁移**（物理表保留） | 保留死实体=永久维护无用途代码面；DROP 表违背 FR-006 与红线；只删代码不升版本=identity hash 校验崩溃 |
| D2 | 空态判定进 `TodoRepository`：快照一次加锁返回 `TodayTodosSnapshot(items, anyEnabled)` | 覆盖层自行推导需暴露全量列表给视图层，违背"排序/过滤不在视图层"既有分工（005 D 系） |
| D3 | 空态**二分**：无启用=引导添加；有启用今日不轮到=告知 | 单一空态会对"有待办但今天都不轮到"的用户给错误的"去添加"引导 |
| D4 | 空态卡复用 todoBlock 仅标题行（形态同全完成态），前导 spacer 常驻 VISIBLE | 新增独立组件多余；继续隐藏会造成布局跳变且违背 FR-003 |
| D5 | DataStore 残留键不清理，读写代码全删 | 为无收益的残留清理写迁移逻辑不值 |
| D6 | HomeTab 摘 TIPS；`rememberSaveable` 恢复走 `entries.find{}` 兜底 TODOS | 升级后进程恢复旧存档的 "TIPS" 枚举名，`valueOf` 直接崩 |
| D7 | 共享字符串改中性键（`hints_dismiss`→`common_dismiss`、`hints_char_count`→`common_char_count`，调用点仅 TodoScreen 两处×2） | `hints_` 前缀键在功能退役后是语义噪音 |
| D8 | 服务侧主线程同步取数只剩 `todayTodos`；`BlockingOverlay` 构造参数删 `hint` | 传空串保留参数=为退役功能留死参数 |

## Risks & Mitigations

- **升级路径**：MIGRATION_8_9 空迁移唯一作用是让 Room 接受实体集变化的 identity hash；手建 v8 库（含 hints 行）→ v9 打开 → raw query 断言 hints 行数不变；既有 1→8 链测试零改动。
- **进程恢复崩溃**：Tab 枚举收窄后旧进程存档值越界——D6 兜底 + 手测（杀进程重进）。
- **空态误导**（引导添加 vs 今天不用做）：D3 二分 + `TodoRepositoryTest` 行为用例锁定。
- **视觉回归**：删提示语行后标题/待办卡/倒计时间距失衡——spacer 常驻规则进契约，quickstart 四态手测。
- **误删共享资源**：`hints_dismiss`/`hints_char_count` 被待办页复用——删除清单化（Architecture 一节），门②编译守住。
- **统计锚点回归**：验证标准 4——今日四数渲染代码不在改动面，CI + quickstart 双确认。

## Implementation Order

1. **data 层**：Hint 实体/DAO 出库 + v9 空迁移 + AppModule 摘除（先保编译闭环）
2. **repository/datastore**：删提示语仓储与游标/开关；`TodoRepository.todayTodos` 快照类型化（含 KDoc 同步）
3. **服务与覆盖层**：service 摘 hint；`BlockingOverlay` 删提示语行 + 空态二分支 + 2 条新 strings
4. **UI**：HomeTab 收窄 + 恢复兜底；删 HintList 页/VM；共享键改名
5. **测试**：删 3 个提示语测试；`TodoRepositoryTest` 更新+空态用例；`AppDatabaseMigrationTest` +v8→v9
6. **文档**：README「已规划退役」→「已退役」现状化、AGENTS.md 现状化、tasks.md 勾选、commit

## Verification

- 门①②：`:app:assembleDebug` / `:app:testDebugUnitTest` 全绿
- 行为测试：快照四态派生（CONTENT/ALL_DONE/EMPTY_NONE_ENABLED/EMPTY_NONE_DUE）；v8→v9 迁移 hints 行留存
- 手测：[quickstart.md](quickstart.md) 场景 S1–S7（真机覆盖升级）
- 锚点：统计页今日四数、待办页、拦截时序零回归

## Project Structure

### Documentation (this feature)

```text
specs/009-retire-hints/
├── plan.md                    # 本文件
├── research.md                # Phase 0：技术决策与替代方案
├── data-model.md              # Phase 1：Room/DataStore/快照类型变更
├── contracts/todo-card-overlay.md  # Phase 1：覆盖层内容层 + 导航契约
├── quickstart.md              # Phase 1：真机手测场景
└── tasks.md                   # Phase 2（/speckit-tasks 生成，非本命令产物）
```

### Source Code

改动面**单一事实源**见「Architecture: 改动清单」一节（单 `:app` 模块，包结构不变，不新增目录）。

## Complexity Tracking

无宪法违例需要豁免（Constitution Check 全 PASS）。
