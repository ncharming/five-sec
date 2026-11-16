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
- [x] 11. 可选域判定抽 `OnceDueDateSelection` 纯逻辑 + 判定表 6 例（2026-11-13：锚定周一~周日全可选、
       仅过去日不可选——星期几不参与任何分支）
- [x] 12. FiveSecDialog 标准窗口结构：标题/按钮固定 + 独立垂直滚动内容区 + 屏高 85% 封顶
       （2026-11-13 修复「整块不可滚」——长表单与日历超屏被硬裁，日历底部未来周六/日点不到
       即「只能选工作日」观感的根因；短弹窗 fill=false 观感不变）
- [x] 13. 当天单次组置顶卡首（2026-11-16 用户拍板，方案修订注）：`groupByRule` 增 `today` 参数
       （与灰显派发同源）、置顶键字符串等值比较 + 脏 today 不置顶兜底；TodoScreen 传 VM `today`；
       判定表补置顶三例（置顶/未来不置顶/空 today 兜底）；README 与 AGENTS.md 术语表同步
