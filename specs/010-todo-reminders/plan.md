# Plan: 待办到点主动提醒（specs/010）

## 决策记录（/grill-me 两轮质询逐项拍板）

1. **时刻语义**：每条待办一个可选 `HH:mm`（秒恒 00）；重复类=每个轮到日该时刻响，单次=有效期日当天该时刻响；完整时间点由既有日期列推导，不另存。
2. **默认关**：不设时刻=零排程，存量用户零感知。
3. **允许保存已过时刻，绝不补响**：排程只面向未来。
4. **权限**：声明 `SCHEDULE_EXACT_ALARM` + 应用内引导开「闹钟和提醒」；未授权自动降级非精确闹钟（仍响、可能漂移）+ 待办页提示。
5. **恢复**：`RECEIVE_BOOT_COMPLETED` 开机全量重排 + 每次进 App 兜底重排；另监听 `TIME_SET`/`TIMEZONE_CHANGED`/`MY_PACKAGE_REPLACED`；强停后不响=系统机制，README 记载。
6. **呈现**：全屏提醒页（full-screen intent 通知载体；锁屏之上显示、自动亮屏），14+ 引导放行「全屏显示」。
7. **感官**：系统默认闹钟铃声 + ALARM 音量流 + 闹钟式长震动；零内置音频。
8. **聚合**：同一分钟多条合成一次响铃、一页列出（技术：全局单一「下一响」闹钟，到点反查全量）。
9. **错过不补响**：静默跳过已过时刻。
10. **勿扰不穿透**：README 说明自行加闹钟例外。
11. **不留痕不落表**：提醒不写任何事件表、不进统计、无「已提醒」标记。
12. **轮到联动**：只在「轮到且启用」日响（`isDue` 三处同源）；间隔规则拖延顺延期连响到完成；停用/过期单次不响。
13. **响前查库**：当天已完成静默跳过；回滚且时刻未到照常响；已过不补。
14. **提醒页操作**：可逐条勾完成（复用 `setCompleted` 双写）+ 关闭；全勾自动关；**不做贪睡**；「勾选仅在待办页」扩展为「待办页与提醒页」。
15. **60 秒自动停铃**：无操作 1 分钟后停铃收静默通知，点开可回提醒页完成。
16. **权限引导**：首次保存提醒时刻请求 `POST_NOTIFICATIONS`（13+）；拒绝不阻断；待办页横幅按缺失项逐条提示、补齐即消。

## 实现顺序（依赖驱动）

1. **domain/db**：`Todo.reminderTime` 列 + `MIGRATION_9_10` + Room v10 + `TodoDao.updateReminderTime/findByIds` + AppModule 注册。
2. **纯逻辑**：`reminder/TodoReminderPlanner`（零 Android import）——`isValidReminderTime` / `isDueForMinute`（响前过滤）/ `nextTriggerAt(todo|todos)`（单条与全量、注入 now+ZoneId、370 天视界）。
3. **repository**：`setReminderTime`（校验：空或合法 HH:mm）+ `add` 带 reminderTime + `allTodosOnce`/`findByIds`（供协调器与提醒页）。
4. **reminder 组件**：`ReminderChannels`（alarm/notice 双渠道）、`TodoReminderScheduler`（单一闹钟 + 精确/降级）、`TodoReminderCoordinator`（observeAll 收集即重排 + `rescheduleNow`）、`TodoReminderReceiver`（触发分钟反查→FSI 通知→排下一响）、`BootReceiver`、`ReminderRinger`（MediaPlayer USAGE_ALARM 循环 + 波形震动）、`TodoReminderActivity` + `TodoReminderViewModel`（Compose 全屏页、逐条勾、60s 停铃收通知）。
5. **UI**：TodoEditDialog 提醒区（TimePicker + 清除）、行内「提醒 HH:mm」角标、权限横幅（通知/精确/全屏三项）、首存权限请求；TodoViewModel 转发。
6. **接线**：Manifest（5 权限 + 2 receiver + activity）、FiveSecApp（渠道创建 + 启动重排）、strings.xml、通知小图标。
7. **测试**：PlannerTest 判定表（含跨日/时区/顺延/单次过期）、迁移测试 v9→v10（并补全既有用例迁移链）、TodoRepositoryTest 提醒列口径、FakeTodoDao 扩展。
8. **收尾**：README + AGENTS.md 口径同步、tasks.md 勾选、assembleDebug + testDebugUnitTest、commit 推 master。

## 风险与对策

- **Room identity hash 校验**：ADD COLUMN 列定义必须与实体 schema 逐字一致（`TEXT NOT NULL DEFAULT ''`，沿用 006/007 已验证模式）。
- **Hilt 注入 BroadcastReceiver**：`@AndroidEntryPoint` 支持 receiver；配合 `goAsync()` + 注入的应用级 scope 收尾 `finish()`，不新建服务。
- **到点分钟漂移**：非精确降级下闹钟可能晚响数分钟——响铃判定用**排程时刻**（intent extra 携带）的本地 HH:mm 反查，而非「当前时间」，晚到也能正确聚合当分钟的条目。
- **前台后台启动限制**：全屏页只能经 full-screen intent 通知通道拉起（官方豁免路径）；13+ 无通知权限时不拉起不响（横幅已教育），不做任何后台 Activity 直启的黑魔法。
- **提醒页与待办页并发写**：勾选走同一定向 UPDATE + 完成事件 upsert（(todoId, 当日) 唯一），天然防重；提醒页展示即时按最新库态过滤。
