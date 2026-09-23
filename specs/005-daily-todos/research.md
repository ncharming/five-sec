# Research: 每日待办与拦截流程打通

**Date**: 2026-09-23 | **Spec**: [spec.md](spec.md)

Phase 0 技术决策与权衡。结论先行：**零新增依赖**——Room/DataStore/Hilt/icons-extended 均为既有能力，本特性是纯组合。

## R1. 每日重置的实现：`lastCompletedDate` 惰性判定 vs 完成记录表

| 方案 | 说明 | 代价 |
|---|---|---|
| **A. 单表 + `lastCompletedDate`（选定）** | 条目上记"最近完成日"，完成判定 = 该值 == 今天；跨日自动失效 | 只能回答"今天完成没有"，无历史 |
| B. 完成记录表 `(todoId, date)` | 保留完整勾选历史，可做完成率统计 | 需要 join、无清理边界、v1 明确无统计诉求 |

**决策**：A。共识明确 v1 待办不进统计页、不留历史；A 零 join、零清理任务、快照查询即展示口径。将来若做完成率统计，B 可作为 v6+ 增量叠加，A 的字段不构成障碍。

## R2. 覆盖层数据注入：快照模式（对齐 hints）

覆盖层创建发生在 `onAccessibilityEvent` 主线程，禁止挂起/IO。`TodoRepository` 与 `HintRepository` 同构：后台协程收集 DAO Flow 进 `synchronized` 快照，服务侧同步读内存。`BlockingOverlay` 保持"手动构造 `BlockingViewModel` + 构造参数注入数据"形态，新增 `todos: List<TodayTodo>` 参数，与 `hint: String` 并列。**不**让 overlay 持有 ViewModel 以外的数据源。

## R3. 提示语循环游标的存储位置

| 方案 | 说明 | 结论 |
|---|---|---|
| **A. DataStore 新整型键（选定）** | `five_sec_settings` 加 `hint_cycle_cursor`；读侧 Flow 收集进快照，写侧收敛 | DataStore 已在依赖内；跨进程重启持久；写路径可收敛 |
| B. Room `hints` meta 行 | 与提示语数据同库 | 混入业务表的元数据行，语义脏 |
| C. SharedPreferences | 简单 | 引入第二种持久化栈，违反单一存储方向 |

**写路径乱序防护**：`takeNextHint` 推进游标后异步落库；若直接 `scope.launch { write }`，两次连续拦截的写协程可能乱序执行导致游标回退一格（表现：重复展示同一条）。改为写入 `MutableStateFlow<Int?>` 收敛队列，单一收集器只持久化最新值（conflate 语义），最终态恒正确。

## R4. 游标口径：存"下一次下标"，读时取模

游标存 `index + 1`（下一次应取的下标），读取时对当前序列长度取模。序列增删（加提示语/删提示语）后自然延续——不承诺增删瞬间"绝不跳条/绝不重条"（严格轮转需按 id 记"最近轮过谁"，复杂度与"轮着来"的诉求不成比例，共识确认为可接受取舍）。游标恒为小非负整数，无溢出顾虑。

## R5. 栈式提示退役的清理面

生产者只有拦截页输入行一处（004 唯一入口），输入行删除后整条链路成死代码。清理面：`BlockingOverlay`（输入框/保存按钮/反馈 TextView/showFeedback/`onSaveHint` 参数/相关常量）、服务侧 `onSaveHint` 接线、`HintRepository`（栈快照/`consumedPending`/栈分支/`pushStackHint`/`stackSnapshotSizeForTest`）、`HintKind.STACK` 常量、4 条 `blocking_hint_*` 文案、三个测试文件的栈用例。**存量数据**：v4→v5 迁移 `UPDATE hints SET kind='pool' WHERE kind='stack'`（共识：用户手打的文字是资产，改挂为池只花一条 SQL）。`kind` 列保留不删——避免为删列做表重建迁移，收益不成比例。

## R6. 信息架构重构的落点

- `HomeTab` 枚举直接改（`FIVE_SEC` → `TODOS`，`APP_LIST` → `INTERCEPT`）：`rememberSaveable` 存枚举名，升级安装必经进程重启、无旧名残留路径，无兼容包袱。
- 新建 `InterceptScreen` 承载合并页（总开关卡 + 应用清单 + 两个既有 ViewModel 并挂）；`SettingsScreen`/`AppListScreen` 整文件删除而非标记废弃——仓库无弃用代码先例，留着必被误用。
- `SettingsViewModel`/`AppListViewModel` 保留原名与职责（一个管 DataStore 开关、一个管应用清单），不因页面合并而合并 VM——职责不同，合并只会制造一个胖 VM。

## R7. 覆盖层待办卡片的渲染形态

纯 TextView 组合（与覆盖层既有实现一致，不引 Compose）：
- 标题行 `TextView`（14sp 加粗 onSurface）：「今日待办 D/T」或完成态整行文案；
- 条目区 `TextView`（14sp onSurfaceVariant）：未完成条目以 `\n` 拼接（`○ ` 前缀，最多 3 行）+ 超出折叠行；
- 启用数为 0 时整块 `GONE`，同时隐藏其前导 spacer——布局回到与现状逐像素一致。

上限 3 条与折叠行是防溢出约定：倒计时 5 秒内可读的行数上限，超出部分引导用户去待办页看全量。

## R8. 日期口径与测试

复用 `DateUtil.todayString(now, zone)`（既有，默认 `systemDefault()`）。每日重置是纯字符串比较，不需要新日期工具；测试显式传 `ZoneId`（CI 在 UTC 跑的历史教训，commit fe46089），跨日用固定 `ZoneId` + 构造时间戳验证，不依赖真实时钟。
