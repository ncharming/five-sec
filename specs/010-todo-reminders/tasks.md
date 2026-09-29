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

## 修复轮（真机反馈：只亮横幅不响铃、点了才响）

- [x] 16. 响铃前台服务 ReminderAlarmService：铃声/震动唯一持有方，Receiver 到点直接启动——与页面拉起解耦，息屏/亮屏/FSI 被拒都即时响（mediaPlayback 类型 + WAKE_LOCK 保 CPU 不睡 + 60s 超时收底 + START_NOT_STICKY）
- [x] 17. FSI 链路保留并归服务：前台通知即 FSI 载体（息屏/锁屏直拉全屏页）；亮屏由服务补拉页面（无障碍运行=后台启动豁免，Activity singleTop + onNewIntent 去重重装）；Receiver 后台 FGS 启动被拒 → 退化一次性响铃通知
- [x] 18. 提醒页升级：完整内容展示（多行原文不截断）+ 每条「完成」按钮；页面不再持有铃/超时（onUserInteraction→STOP_RING，finish()→FINISH 统一撤服务）；通知渠道 v2 静音（防渠道一声+服务循环双响，v1 渠道启动时删除）
- [x] 19. 无通知权限不再静默跳过（响铃优先拍板）；文档同步：spec FR-002/FR-004 与边界、scheduling 契约、quickstart 息屏/亮屏/无障碍关闭场景、README 与 AGENTS.md 口径；188 tests 全绿

## 修复轮二（真机反馈：息屏不触发；页面视觉差；完成按钮换复选框）

- [x] 20. 息屏 Doze 根治：`SCHEDULE_EXACT_ALARM`（侧载默认不授予 → 降级非精确 → Doze 推迟到亮屏）换成 `USE_EXACT_ALARM`（闹钟类应用权限，安装即自动授予、不可撤销，Doze 仍准点）；`canScheduleExactAlarms` 降级分支与横幅保留作 ROM 异常兜底；Manifest/横幅文案/README/spec FR-006/契约表/quickstart Doze 核心验证场景同步
- [x] 21. 提醒页视觉重排（功能不变）：品牌绿渐变背景 + 圆形闹钟徽章 + 大号时刻（display 级）+ 逐条独立卡片（surfaceContainerLow、大内边距）+ 底部整宽关闭按钮；宽度上限 520dp、可滚动
- [x] 22. 交互调整：条目「完成」按钮改为**前置复选框**勾选即完成（同款 setCompleted 双写，勾后即移除）；删除 todo_reminder_action_complete 字符串、screen_title 拆为纯标签 + 独立大号时刻

## 修复轮三（真机复验：息屏仍不触发；亮屏正常 → 两层根因齐修）

- [x] 23. 调度层改 `AlarmManager.setAlarmClock()`（市面闹钟应用通行实现）：AOSP 最高优先级闹钟、触发时系统**真正退出 Doze**、完全不需要精确闹钟权限（删除 canScheduleExactAlarms 降级分支，不存在静默掉非精确被推迟的路径）；状态栏「即将闹钟」图标点开回主页；USE_EXACT_ALARM 声明保留仅作 12+ 后台 FGS 启动豁免兼容面
- [x] 24. 电池优化白名单引导（OEM「应用速冻」是息屏不响的头号元凶，代码层绕不过）：`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限 + `isIgnoringBatteryOptimizations` 检查 + 待办页横幅一键 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 弹窗（取代已无意义的精确闹钟横幅）；文档同步 spec FR-006/边界、契约调度与权限表、quickstart 息屏核心验证、README 平台限制

## 修复轮四（链路审计：降级兜底自 minSdk 26 起从未可闻 + 服务侧第二道闸无声自灭）

- [x] 25. 降级一次性响铃通知改挂独立「兜底响铃渠道」（`todo_reminder_alarm_fallback`：渠道级默认 ALARM 声 + 闹钟式震动波形）：API 26+ 通知声音/震动由**渠道**决定、builder `setSound/setVibrate` 恒被渠道覆盖——原「builder 挂铃声」写法从未真正响过；载体渠道仍钉死静音（铃声唯一来源在服务的 MediaPlayer，防双响）
- [x] 26. 通知装配抽出 `ReminderNotifications`（Receiver 主路径/服务降级共用一份，防预览/FSI/requestCode 口径漂移）；`ReminderAlarmService.startForeground` 被拒（OEM 变体的第二道闸）不再无声自灭——按本场口径重查库发兜底响铃通知再收口，两层启动闸任一被拒都仍可闻
- [x] 27. 测试：`ReminderNotificationsTest`（Robolectric）钉渠道契约（主路径静音载体 / 降级挂兜底渠道 / 兜底渠道 ALARM 声+震 / 载体渠道钉死静音）；README 通知权限口径修正（未授权仍响铃）+ quickstart 降级验证场景
