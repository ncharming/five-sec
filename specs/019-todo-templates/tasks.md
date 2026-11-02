# Tasks: 待办习惯模板（specs/019）

- [x] 1. 资源：strings.xml `string-array todo_templates`（5 条中文习惯）
- [x] 2. UI：`TodoEditDialog` 增 `showTemplates`（新建 true/编辑 false）+ 模板 chips 区（点击整体替换草稿，字数/规则/提醒照常）
- [x] 3. 测试：纯展示层无新逻辑——沿用既有弹窗行为测试口径（TodoViewModelTest/PlannerTest 全绿），quickstart 手测补 chips 场景
- [x] 4. 文档：README 待办段补模板说明
- [x] 5. `:app:assembleDebug` + `:app:testDebugUnitTest` 全绿，提交推送 master
