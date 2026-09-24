# Research: 提示语功能退役

**Date**: 2026-09-24 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

结论速览：D1 Room 摘实体+空迁移 · D2 空态判定进 TodoRepository 快照 · D3 空态二分 · D4 空态卡复用 todoBlock · D5 DataStore 残留不清理 · D6 Tab 恢复兜底 · D7 共享字符串改中性键 · D8 服务/覆盖层签名收窄。逐项依据如下。

## R1 Room 层处置：摘除实体声明 + v9 空迁移（D1）

- **Decision**: `@Database` entities 摘除 `Hint`、删 `hintDao()`，版本 8→9，`MIGRATION_8_9.migrate()` 体为空（零 SQL）。
- **Rationale**: Room 按声明实体集计算 identity hash 并在 `room_master_table` 校验。只删代码不升版本，老安装打开即抛 "Room cannot verify the data integrity" 崩溃；升版本+空迁移后，hash 重写为 v9 值，**未声明的物理表不参与校验**，`hints` 表与行原样留在 `five_sec.db`——恰好兑现 FR-006"只读保留、不删表、不清空、不做破坏性迁移"。
- **Alternatives**:
  - 保留 `Hint` 实体+DAO 但无调用者：零风险但留下永久死代码面（DAO/实体/测试都要养），违背"只删不增"的冻结口径；
  - MIGRATION_8_9 里 `DROP TABLE hints`：违背 FR-006 与"数据只进不出"红线，直接否决；
  - DataStore/文件导出 hints 数据后再删表：功能退役无导出需求（B2 已否决），复杂度无收益。

## R2 空态判定归属与快照形状（D2）

- **Decision**: `TodoRepository.todayTodos(today)` 返回值从 `List<TodayTodo>` 改为 `TodayTodosSnapshot(items, anyEnabled)`，一次 `synchronized(lock)` 内同时产出两值；`anyEnabled = snapshot.any { it.isEnabled }`（全量条目，含停用外的一切启用条目，无论今日是否轮到）。
- **Rationale**: 空态需要"是否存在任何启用条目"这一全量信息，而现有过滤链（isEnabled + isDue）把它丢掉了。归属仓库层与 005 起的分工一致——`BlockingOverlay` 的 KDoc 明言"排序规则不在视图层"，过滤/派生同理；一次加锁保证 items 与 anyEnabled 来自同一时点快照，视图层零推导、服务侧零改动语义。
- **Alternatives**:
  - 覆盖层/服务再调一个 `hasEnabledTodos()`：两次加锁取值可能跨一次 Room 发射，时点不一致（理论上 D/T 与空态标签矛盾）；
  - 把全量 `List<Todo>` 传给覆盖层自行过滤：视图层拿到远超所需的领域对象，且复刻 isDue 谓词违背单一事实源。

## R3 空态二分与文案（D3）

- **Decision**: 两分支——`anyEnabled=false` → 引导添加；`anyEnabled=true && items.isEmpty()` → 告知今日不轮到。建议文案：`还没有今日待办 · 打开「五秒」添加`、`今天没有轮到的待办`（措辞实现可调，语义锁定）。
- **Rationale**: "有待办但今天都不轮到"（如仅周五条目遇周三）是 007 重复规则引入后的真实状态；对这种用户显示"去添加"是错误引导，也和待办页既有的"今天不用做"语言体系冲突。两分支与 spec FR-003 一一对应。
- **Alternatives**: 单一空态文案（如"暂无今日待办"）——对两类用户各错一半；空态直接跳待办页——违背"覆盖层只读、5 秒不引入新交互"边界（spec Edge Cases）。

## R4 DataStore 残留（D5）

- **Decision**: 删 `BuiltinHintsSetting` 接口/实现、`hintCursor`/`setHintCursor`、`Keys.BUILTIN_HINTS_ENABLED`/`HINT_CURSOR`、`AppSettings.builtinHintsEnabled` 字段；设备上已写入的两个键留在 Preferences 文件不清理。
- **Rationale**: FR-007 明确"MUST 停止读写；残留值 MAY 保留"。清理残留需要专门的一次性 edit 逻辑，为不可见、无行为差异的目标写代码，不值。
- **Alternatives**: 启动时读一次删两键——残留清理非需求，代码面反向增加。

## R5 Tab 收窄与进程恢复安全（D6）

- **Decision**: `HomeTab` 摘 `TIPS`；`rememberSaveable` 恢复改为按名查找 + 兜底：`HomeTab.entries.find { it.name == saved } ?: HomeTab.TODOS`。
- **Rationale**: 现实现以枚举 `name` 字符串持久化选中 Tab。升级覆盖安装后，若进程在旧版本被杀、新版本恢复（`rememberSaveable` 走 Bundle 重建），存档里的 `"TIPS"` 在收窄后的枚举上 `valueOf` 抛 `IllegalArgumentException` 崩溃。兜底查找一次性消除该窗口。
- **Alternatives**: 保持枚举序号持久化（`rememberSaveable { mutableStateOf(HomeTab.TODOS) }` 默认按 name? Compose 对 enum Saveable 默认存 name 字符串? 实际上 SaverKit 存的是可 Bundle 化值，enum 走 `autoSaver` 以 name String 存储）——无论哪种序列化形态，"旧值不存在于新枚举"的问题同源，查找+兜底是最小修复。

## R6 字符串清理范围（D7）

- **Decision**: 整段删除 `hints_title/subtitle/add/empty/input_hint/add_confirm/delete/builtin_section/builtin_switch/builtin_save_failed` 与数组 `blocking_exercise_hints`；被 TodoScreen 复用的 `hints_dismiss`/`hints_char_count` 改中性键 `common_dismiss`/`common_char_count`（调用点：TodoScreen 添加/编辑弹窗取消按钮 ×2、字数行 ×2 处，编译器守住全部改点）；新增 `blocking_todos_empty_none`/`blocking_todos_empty_none_due`。
- **Rationale**: `hints_` 命名空间整体退役最干净；两个共享键语义本是"通用取消/字数计数"，改中性名消除"提示语残留"的误读。
- **Alternatives**: 保留 `hints_dismiss`/`hints_char_count` 原名只删其余——省 4 处改点，但命名空间半死不活；重命名波及 InterceptScreen 的 `settings_a11y_hint_*`/`app_list_full_hint` 等"hint=提示"词根键——不属提示语功能（语义是 guidance hint），明确排除在外。

## R7 服务侧与覆盖层签名收窄（D8）

- **Decision**: `AppBlockerAccessibilityService` 摘 `hintRepository()` EntryPoint 成员与 `takeNextHint` 调用（含 `blocking_exercise_hints` 资源读取）；`BlockingOverlay` 构造参数删 `hint`、删 `hintText` TextView 与对应 spacer 节奏重排（见 contracts 布局）。
- **Rationale**: 服务主线程同步取数从两处减为一处（`todayTodos` 快照），覆盖层构造路径变短且不引入新异步（spec Edge Cases 明确要求保持快照注入模式）。参数保留空串会让"提示语已退役"这件事在签名上不可见。
- **Alternatives**: `hint` 参数保留传 `""`——死参数误导后来者；TextView 留着 setText 空串——同上。

## R8 测试面变化

- **Decision**: 删 `HintDaoTest`/`HintListViewModelTest`/`HintRepositoryTest`（提示语链路整体退役，测试随代码删）；`TodoRepositoryTest` 跟随快照类型更新，并新增空态二分用例（无启用 / 有启用今日不轮到 / 全完成 / 部分完成 四态派生）；`AppDatabaseMigrationTest` 新增 v8→v9：手建 v8 schema（含 hints 表与行）→ v9 打开 → raw query 断言 hints 行留存 + 其余表数据不变。
- **Rationale**: 对齐仓库"迁移回归全靠手建库模式"（`exportSchema=false` 无 schema json）；hints 已无 DAO，行留存断言只能走 `SupportSQLiteDatabase.query` raw SQL。行为改动（空态派生）按验证标准 2 必须有测试。
- **Alternatives**: 不删旧测试改 `@Ignore`——死测试是维护噪音；迁移测试只验"不崩"不断言行留存——FR-006 的数据零破坏就没有回归锚。

## 退役清单（实现对照，grep 已核实无其他消费者）

- **主代码**：`domain/model/Hint.kt`、`data/db/HintDao.kt`、`data/repository/HintRepository.kt`（含 `HintCursorStore`）、`data/datastore/DataStoreHintCursorStore.kt`、`settings/ui/HintListScreen.kt`、`settings/viewmodels/HintListViewModel.kt`。
- **部分修改**：`AppDatabase.kt`、`AppModule.kt`、`SettingsDataStore.kt`、`AppSettings.kt`、`SettingsViewModel.kt`、`TodoRepository.kt`、`AppBlockerAccessibilityService.kt`、`BlockingOverlay.kt`、`HomeScreen.kt`、`Dialogs.kt`（注释）、`strings.xml`。
- **测试**：删 3 个 Hint 测试；改 `TodoRepositoryTest`、`AppDatabaseMigrationTest`。
- **注释同步**：`TodoRepository`/`BlockingOverlay` KDoc 中"对齐 HintRepository""提示语由服务侧注入"等表述随退役改写。
