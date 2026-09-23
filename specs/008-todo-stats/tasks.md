# Tasks: 任务完成统计

**Feature Branch**: `008-todo-stats`

- [x] 1. domain/model/TodoCompletion.kt：四列实体 + (todoId, completedDate) 唯一索引，中文 KDoc 讲清快照语义
- [x] 2. data/db/TodoCompletionDao.kt：upsert(REPLACE)/deleteByTodoAndDate/observeCountByTodoBetween/observeEarliestDate + TodoRangeCount 投影
- [x] 3. AppDatabase v8：注册实体与 DAO；MIGRATION_7_8 建表+建索引（列定义与 Room schema 逐字一致）
- [x] 4. AppModule：迁移链补 MIGRATION_7_8；provideTodoCompletionDao
- [x] 5. TodoDao.findById（setCompleted 取文本快照用）
- [x] 6. TodoRepository：ctor 注入 completionDao；setCompleted 双写（勾选 upsert 含快照/取消删当日）；observeCompletionCountsByTodoBetween/observeEarliestCompletionDate 转发
- [x] 7. DateUtil：dateStringToMillis / millisToDateString（带 ZoneId，非法输入 null）
- [x] 8. TodoTodayStatsCalculator 纯函数：任务(D/T 同口径)/完成/过期三数
- [x] 9. StatsViewModel：todoToday StateFlow；todoRangeStats（selectedPeriod flatMapLatest）；availablePeriods 取两表最早
- [x] 10. StatsScreen 三态重构：主页三卡+两入口；拦截二级页原样搬迁；任务二级页（总完成卡+条目卡+空态）；BackHandler
- [x] 11. strings.xml：今日任务三数/入口/二级页文案
- [x] 12. TodoCompletionDaoTest：upsert 当天唯一/取消删除/区间聚合/GROUP BY 排序（in-memory Room + Robolectric）
- [x] 13. TodoRepositoryTest：构造签名更新全量用例；新增勾选写快照/取消删/重勾不重复用例
- [x] 14. TodoTodayStatsCalculatorTest + DateUtilTest 换算助手用例
- [x] 15. AppDatabaseMigrationTest：4 个既有用例迁移链补 MIGRATION_7_8；新增手建 v7 库 → v8 用例（老待办保留+新表可用）
- [x] 16. `gradle :app:assembleDebug` + `:app:testDebugUnitTest` 全绿
- [x] 17. README 统计章节同步（双块结构/完成事件表/口径）+ specs 索引加 008
- [x] 18. Conventional Commit `feat(stats): 任务完成统计与统计页双块重构（specs/008）` 推 master，CI 绿
