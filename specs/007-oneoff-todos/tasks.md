# Tasks: 一次性待办与过期分类（specs/007）

- [x] 1. domain：Todo 实体新增 createdAt/dueDate 两列（TEXT 默认空串）+ KDoc 口径更新
- [x] 2. util：TodoRecurrence 新增 REPEAT_ONCE=3、isDue 签名加 dueDate 参数与仅今天分支、新增 isExpired 纯函数
- [x] 3. domain：TodoRule.ONCE 工厂
- [x] 4. db：AppDatabase v7 + MIGRATION_6_7（两条 ADD COLUMN）+ AppModule 注册
- [x] 5. dao：updateRecurrence 定向更新加 dueDate 列；新增 setDueDate、purgeCompletedOneOffs
- [x] 6. repository：注入 TimeProvider；add 写 createdAt/dueDate；setRecurrence 同步写 dueDate（转仅今天=当天/转回清空）；revive；observeAll 收集器内惰性清理
- [x] 7. viewmodel：TodoUiState(todayRows, expiredRows) 分区派生（僵尸行过滤、过期按 dueDate 升序）+ 副行日期 + revive 转发
- [x] 8. ui：TodoScreen 两卡分区、过期行（无勾选/开关，改为今天/删除）、四选一规则编辑器（仅今天提示文案）、行内副行日期、详情弹窗带日期
- [x] 9. overlay：BlockingOverlay 条目截 12 字+…、卡片内标题+条目左对齐
- [x] 10. strings：todos_rule_once / todos_rule_once_hint / todos_section_today / todos_section_expired / todos_date_unknown / todos_menu_revive
- [x] 11. 测试：TodoRecurrenceTest 仅今天与过期判定表（含脏数据防御/时钟回拨）
- [x] 12. 测试：TodoRepositoryTest add 写两列、setRecurrence dueDate 口径、revive、惰性清理、快照含一次性当天/排除过期
- [x] 13. 测试：TodoViewModelTest 两区分组派生、僵尸行过滤、副行日期
- [x] 14. 测试：AppDatabaseMigrationTest v6→v7 手建库用例（零丢失、空串回填、新列可读写）
- [x] 15. 验证：`gradle :app:assembleDebug` + `gradle :app:testDebugUnitTest` 全绿（141 tests passed）
- [x] 16. 文档：README 待办段与 specs 索引同步；commit（Conventional Commits 中文主题）推 master
