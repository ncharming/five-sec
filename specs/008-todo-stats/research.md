# Research: 任务完成统计

**Feature Branch**: `008-todo-stats`

## RQ1: 历史完成数据从哪来？

**结论（用户拍板）**：新增 todo_completions 完成事件表，勾选落一行、取消即删。

排查过的替代：
- **现状直算**：todos.lastCompletedDate 是单值字符串，每次勾选覆盖——只有"最近一次完成日"，无历史可聚合；createdAt 是创建日不是完成日。
- **每日快照表**：能算历史完成率，但需要每天跑一次写入；本应用是纯离线惰性架构（每日重置、惰性清理全是加载路径求值，无任何后台任务/WorkManager），日切无可靠触发点，漏一天数据就永久缺分母。
- **interception_events 模式复用**：勾选=事件、聚合=查询时实时 GROUP BY——与本应用既有统计架构完全同构，零新基建。

## RQ2: 勾选可撤销，事件表怎么保证不重不漏？

**结论**：(todoId, completedDate) 唯一索引 + OnConflictStrategy.REPLACE upsert（勾选）/ 定向 DELETE（取消）。

- 同一天勾-取消-再勾：行删了再插（或 REPLACE 覆盖），当日计数恒 ≤1。
- 取消只发生在当天（待办页勾选框只在"轮到且启用"行上，跨日自动失效不可勾），DELETE 按 (todoId, 今天) 定位足够。
- 该表不在"只增不删"红线内（红线仅 interception_events，历史留存是拦截事件的产品价值；待办完成行的价值在当周/当月聚合，条目本身都允许用户删除）。

## RQ3: 历史周期能展示什么指标？

**结论**：只展完成数。

- 分母（当天应做几个）历史不可知：规则三列随时可改、条目可删可停用、一次性完成即清理——任何"反推"都是用今天的规则伪造过去。
- 过期数同理：isExpired 是「相对今天」的推导状态，昨天的过期存量今天可能已被 revive/删除。
- 所以：今日卡三数（任务/完成/过期）都是当下可算的真值；历史页只有完成数是真值。

## RQ4: 二级页导航选型？

**结论**：StatsScreen 页内三态 + BackHandler。

- 根 NavHost 现只有 onboarding/home 两路由；加路由需 NavController 从 MainActivity 穿透 HomeScreen→StatsScreen（回调提升两层），且 push 新路由必然盖住底部 Tab 栏。
- 页内三态：`rememberSaveable` 存当前页；HomeScreen 的 `rememberSaveableStateHolder` 已为每个 Tab 保留状态，切 Tab 回来二级页现场还在；系统返回手势由 BackHandler 兜底回主页。
- 两个二级页共享档位/周期选择（D5）：同一时间视角对比拦截与任务两块数据，VM 状态量减半。

## RQ5: 周期区间怎么映射到完成日期查询？

**结论**：StatsPeriod 的 startMillis/endMillis → yyyy-MM-dd 字符串，SQL 半开区间 [start, end) 字符串比较。

- yyyy-MM-dd 定长零填充，字典序=时间序——007 起就是 todos 日期列的既有约定。
- StatsPeriod 由 LocalDate.atStartOfDay(zone) 生成，毫秒→LocalDate→toString 是无损往返（DateUtil.millisToDateString）。
- 年档位可选范围：拦截 observeEarliestTimestamp()（毫秒）与完成 observeEarliestDate()（日期串，经 dateStringToMillis 归一）取 MIN。

## RQ6: 既有测试的影响面？

- **AppDatabaseMigrationTest**：4 个既有用例的 addMigrations 链都要补 MIGRATION_7_8（缺链打开 v8 库即抛异常，这本身是守卫）+ 新增手建 v7 库用例。
- **TodoRepositoryTest**：FakeTodoDao 需实现新 findById；TodoRepository 构造签名加 completionDao，全部用例的构造行更新；新增双写/取消删/文本快照用例。
- **StatsViewModelTest**：纯逻辑测试，新增 TodoTodayStatsCalculatorTest 覆盖今日三数口径。
- **Robolectric**：TodoCompletionDaoTest 走 in-memory Room（镜像 InterceptionEventDaoTest 模式）。
