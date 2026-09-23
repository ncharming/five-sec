# Technical Plan: 任务完成统计

**Feature Branch**: `008-todo-stats`

## Goal & Non-Goals

**Goal**: 统计页双块重构（今日拦截不动 + 两入口二级页），新增 todo_completions 完成事件表支撑任务完成统计（今日三数 + 日/周/月/年历史）。

**Non-Goals**:
- 不做历史完成率/应做数（分母不可知，FR-006）；
- 不做历史过期数（过期是当前时点推导状态）；
- 不动 interception_events 表结构与写入路径；
- 不引入预聚合表（统计=事件流水实时聚合，v3 删 app_statistics 的决策延续）；
- 不动覆盖层/待办页交互（007 契约不变）；
- 不做独立 Navigation 路由（页内三态足够，用户拍板）。

## Architecture: 三层改动

```
domain/model/TodoCompletion.kt        新实体（四列 + 唯一索引）
data/db/TodoCompletionDao.kt          新 DAO（upsert/删当日/区间聚合/最早日期）
data/db/AppDatabase.kt                v8 + MIGRATION_7_8（建表+建索引）
data/db/TodoDao.kt                    +findById（setCompleted 取文本快照）
data/repository/TodoRepository.kt     ctor +completionDao；setCompleted 双写；三个查询转发
util/DateUtil.kt                      +dateStringToMillis/millisToDateString（周期↔日期串）
settings/viewmodels/TodoTodayStatsCalculator.kt  纯函数：今日三数（D/T 同口径）
settings/viewmodels/StatsViewModel.kt  +todoToday/+todoRangeStats/availablePeriods 双源最早
settings/ui/StatsScreen.kt            页内三态（主页/拦截历史/任务历史）
res/values/strings.xml                新文案
```

## Key Decisions *(from grill-me)*

| # | 决策 | 备选与否决理由 |
|---|---|---|
| D1 | 历史来源=新事件表 todo_completions | 每日快照需可靠日切任务，离线 App 无此基建，易漏 |
| D2 | (todoId, completedDate) 唯一 + 勾选 upsert/取消删除 | 纯 append 双事件流要聚合相减，复杂易错 |
| D3 | 历史只展完成数 | 用当前规则反推历史应做数=伪数据 |
| D4 | 页内三态 + BackHandler | 根 NavHost 加路由要 NavController 穿透且二级页丢底部 Tab 栏 |
| D5 | 两二级页共享档位/周期选择 | 同一时间视角对比两块数据；状态量减半 |
| D6 | 今日任务 x 与覆盖层 D/T 同口径 | 「全部未过期条目」口径与 D/T 对不上，看着困惑 |
| D7 | 完整 spec-kit 交付 | 动 DB+迁移是硬变更，对齐 007 交付形态 |

## Risks & Mitigations

- **迁移失败毁库**：MIGRATION_7_8 只 CREATE TABLE/INDEX（IF NOT EXISTS），不碰既有四表；手建 v7 库迁移测试守住列定义逐字一致；无破坏性兜底（红线）。
- **口径漂移**（今日任务 x vs 覆盖层 D/T）：TodoTodayStatsCalculator 复用 TodoRecurrence.isDue/isExpired 同一批谓词，配单测锁行为。
- **双写半途崩溃**（todos 行写了、事件行没写）：顺序为先 todos 后事件；崩溃窗口内当日 UI 判定以 lastCompletedDate 为准不受影响，再勾选时 REPLACE upsert 自愈；不做跨 DAO 事务（两表无一致性硬约束，统计误差最多当日一次）。
- **删除条目历史失联**：事件行带 todoText 快照，聚合 GROUP BY todoId 展示快照文本。

## Implementation Order

1. domain: TodoCompletion 实体 → DAO → AppDatabase v8 + MIGRATION_7_8 → AppModule 注册（编译闭环）
2. repository: TodoDao.findById；TodoRepository 双写 + 查询转发
3. 纯逻辑: DateUtil 两个换算助手；TodoTodayStatsCalculator
4. VM: StatsViewModel 扩展（todoToday/todoRangeStats/双源最早）
5. UI: StatsScreen 三态重构 + strings
6. 测试: TodoCompletionDaoTest（in-memory Room）、TodoTodayStatsCalculatorTest、DateUtilTest 增补、TodoRepositoryTest 构造签名更新+双写用例、AppDatabaseMigrationTest 全链 v8 + v7→v8 新用例
7. README 同步 + tasks.md 勾选 + commit

## Verification

- 门 ①②：`:app:assembleDebug` / `:app:testDebugUnitTest`
- 行为测试：勾-取消-再勾当日唯一；今日三数口径表；周期区间聚合（跨日插数据核对）；迁移 v7→v8 老数据保留+新表可用
- 锚点：今日拦截三列卡与连击卡渲染代码不动（SC 对齐验证标准 4）
