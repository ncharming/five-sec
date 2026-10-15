# Tasks: 单次待办默认提醒（specs/013）

- [x] 1. 数据层：`Todo.reminderTimes`（@ColumnInfo 钉住物理列 `reminderTime`，CSV 编码，零迁移）
- [x] 2. planner：`DEFAULT_ONCE_REMINDER_TIMES` / `MAX_REMINDER_TIMES=3` / parse/isValid/normalize / `timesOnRuleSwitch` 纯逻辑
- [x] 3. planner：`isDueForMinute` 改「分钟 ∈ 列表」；`nextTriggerAt` 多时刻泛化（当日取最早未来时刻、不补响、完成即静默）
- [x] 4. TodoDao：`updateReminderTimes` 定向单列 UPDATE（SQL 不变）
- [x] 5. TodoRepository：`add`/`setReminderTimes` 多时刻校验（格式/上限中文文案）+ 归一化落库
- [x] 6. TodoViewModel：转发签名更新
- [x] 7. TodoEditDialog：多时刻 chips（点击编辑、TimePicker 编辑态可移除、＋ 添加、满 3 禁增提示）；切单次预填 / 离开还原（autoFilled 标记）
- [x] 8. 行内角标多时刻展示（顿号连接）；权限横幅 anyReminder 口径更新
- [x] 9. strings.xml：新增 add/remove/max/once_hint（参数化），移除 clear
- [x] 10. 测试：TodoReminderPlannerTest（多时刻排程/过滤/完成静默/预填联动/防御）+ TodoRepositoryTest（归一化/上限/缺省）+ 迁移测试签名同步
- [x] 11. 文档：specs/010 data-model + UI 契约同步、specs/013 新建、README、AGENTS.md
- [x] 12. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
