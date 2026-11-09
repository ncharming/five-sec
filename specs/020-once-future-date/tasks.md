# Tasks: 单次待办可选具体日期（specs/020）

- [x] 1. TodoRule：增 `dueDate` 第四列（默认空串，仅单次有语义）+ `once(dueDate)` 工厂；随 data class
       相等参与「没变不发 UPDATE」判定
- [x] 2. TodoRepository：`add`/`setRecurrence` 单次携带合法日落原值、空/非法回落保存当天、重复类清空
       （`onceDueDate` 单一出口）；`normalizeRule` 单次日期格式兜底校验（过去日刻意不拒，注记原因）
- [x] 3. TodoEditDialog：单次段「生效日期」chip + DatePicker（FiveSecDialog 外壳、可选域=今天起、
       确认须选中且不早于今天、编辑回显、切走切回保留）
- [x] 4. TodoRuleGroups：分组键扩四列（单次含 dueDate、重复类归一空）；Group 增 dueDate；单次组按日期升序
- [x] 5. TodoRows.ruleGroupLabel：单次组名「单次/2026-11-23」（空 dueDate 脏形态退裸「单次」）
- [x] 6. TodoScreen：编辑弹窗 existingRule 携带 dueDate（只改日期也触发定向 UPDATE）
- [x] 7. strings.xml：新增 once_date_label / once_date_pick_title / group_once；更新 once_hint、
       reminder_once_hint（「当天」→「生效日」）
- [x] 8. 测试：TodoRuleGroupsTest（不同日期成组/升序/同日合组/重复类脏值归一）+ TodoRepositoryTest
       （未来日落库/非法拒绝/改期直写）+ TodoViewModelTest（建时灰显→到期轮到→跨日过期全流转）
- [x] 9. 文档：specs/020 新建（spec/tasks/quickstart）、README 待办段与提醒段、AGENTS.md 术语表同步
- [x] 10. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿（323 例 0 失败），提交推送 master
