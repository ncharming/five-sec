# Tasks: 待办到点主动提醒（specs/010）

- [x] 1. domain/db：Todo 实体新增 reminderTime 列（TEXT 默认空串）+ KDoc 口径；AppDatabase v10 + MIGRATION_9_10 + AppModule 注册
- [x] 2. dao：TodoDao 新增 updateReminderTime（定向单列 UPDATE）与 findByIds（提醒页装载）
- [x] 3. 纯逻辑：reminder/TodoReminderPlanner（零 Android import）——isValidReminderTime / isDueForMinute / nextTriggerAt(单条|全量，now+ZoneId 注入，370 天视界，绝不补响)
- [x] 4. repository：setReminderTime（空串=清除，非法 HH:mm 拒绝中文文案）；add 带 reminderTime；allTodosOnce / findByIds 转发
- [x] 5. reminder 组件：ReminderChannels（alarm 响铃渠道 + notice 静默渠道）；TodoReminderScheduler（单一闹钟，精确/降级 setAndAllowWhileIdle）；TodoReminderCoordinator（observeAll 收集重排 + rescheduleNow）
- [x] 6. reminder 组件：TodoReminderReceiver（触发分钟反查聚合 → FSI 通知 → 排下一响；13+ 无通知权限静默跳过）；BootReceiver（BOOT/TIME_SET/TIMEZONE_CHANGED/MY_PACKAGE_REPLACED）
- [x] 7. reminder 组件：ReminderRinger（MediaPlayer USAGE_ALARM 循环 + 波形震动，失败只震不崩）+ TodoReminderActivity/ViewModel（全屏页：锁屏显示/亮屏/常亮、逐条勾完成复用 setCompleted 双写、全勾自动关、60s 停铃收静默通知）
- [x] 8. ui：TodoEditDialog 提醒区（TimePicker 24h + 清除）；行内「提醒 HH:mm」角标；onConfirm 扩展 reminderTime；TodoViewModel add/setReminderTime 转发
- [x] 9. ui：待办页权限横幅（通知/精确/全屏三项独立、条件=有已设提醒且对应缺失、ON_RESUME 重查）+ 首次保存 POST_NOTIFICATIONS 请求（拒绝不阻断）
- [x] 10. 接线：Manifest（POST_NOTIFICATIONS/SCHEDULE_EXACT_ALARM/USE_FULL_SCREEN_INTENT/RECEIVE_BOOT_COMPLETED/VIBRATE + 2 receiver + activity）；FiveSecApp 渠道创建与启动兜底重排；strings.xml 全部新文案；通知小图标
- [x] 11. 测试：TodoReminderPlannerTest 判定表（每天当天/跨天、周几命中/不命中、间隔顺延连响、单次今天/过期/已过时刻、停用、空/非法时刻、已完成当天跳过、时区显式、全量取最早）
- [x] 12. 测试：AppDatabaseMigrationTest v9→v10 手建库用例（存量行空串回填、新列可读写）+ 既有全部用例迁移链补 MIGRATION_9_10
- [x] 13. 测试：TodoRepositoryTest setReminderTime 口径（设置/清除/非法拒绝）与 add 落列；FakeTodoDao 扩展新方法
- [x] 14. 验证：`gradle :app:assembleDebug` + `gradle :app:testDebugUnitTest` 全绿（181 tests, 0 failed）；quickstart.md 手测场景过
- [x] 15. 文档：README（待办段新增提醒说明 + 平台限制：强停/勿扰/精确闹钟/全屏显示/OEM 省电）与 AGENTS.md（架构树 reminder/ 包、待办术语「无通知」口径更新）；commit（Conventional Commits 中文主题）推 master
