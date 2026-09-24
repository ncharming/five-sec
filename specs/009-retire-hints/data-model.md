# Data Model: 提示语功能退役

**Date**: 2026-09-24 | **Spec**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md)

变更总览：**无新表、无新列、无字段宽窄变化**——一次"只摘声明"的版本演进（v8→v9）+ 一个新只读快照类型 + 若干退役删除。

## 1. Room（`AppDatabase` v8 → v9）

```kotlin
@Database(
    entities = [TargetApp::class, InterceptionEvent::class, Todo::class, TodoCompletion::class], // 摘 Hint
    version = 9,
    exportSchema = false,
)
```

- 摘除 `abstract fun hintDao(): HintDao`。
- **MIGRATION_8_9：空迁移**（`migrate()` 体零 SQL）——语义：声明实体集变化 → identity hash 变化 → 必须升版本让 Room 重写 `room_master_table`；物理 `hints` 表不 DROP、行不动（FR-006/FR-007）。
- Room 校验边界：打开数据库只校验**声明实体**的表与 hash；未声明的 `hints` 物理表成为"数据墓碑"——可追溯（adb/raw SQL 可查）、应用零读写、无 DAO。
- 红线对照：`interception_events`/`todo_completions` 结构与数据零触碰（FR-009）；无 DELETE、无 `fallbackToDestructiveMigration`。

### 迁移测试口径（`AppDatabaseMigrationTest` 增补）

手建 v8 schema（五表齐 + `hints` 表插 3 行 pool 数据 + `interception_events`/`todos`/`todo_completions` 各插样本行）→ 以 v9 打开 → 断言：

1. 打开不崩、`room_master_table` hash 更新（隐含：走完 8→9 迁移）；
2. `SELECT COUNT(*) FROM hints` 仍为 3（raw query——已无 DAO）；
3. 其余表样本行原样。
既有 1→8 链用例零改动。

## 2. 领域类型（`domain/model/TodayTodo.kt`）

```kotlin
data class TodayTodo(val text: String, val isDone: Boolean)   // 不变

/** 覆盖层待办快照（009）：items 与 anyEnabled 同一锁内产出，时点一致。 */
data class TodayTodosSnapshot(
    val items: List<TodayTodo>,   // 启用且今日轮到，overlayOrder 排序（005/007 口径不变）
    val anyEnabled: Boolean,      // 快照时点是否存在任何 isEnabled 条目（含今日不轮到者）
)
```

### 覆盖层状态派生（纯函数，视图层零推导）

| items | anyEnabled | 状态 | 渲染（见契约） |
|---|---|---|---|
| 非空且含未完成 | * | `CONTENT` | 今日待办 D/T + ○ 未完成 ≤3 行 |
| 非空且全完成 | * | `ALL_DONE` | 今日待办已全部完成 ✓ |
| 空 | false | `EMPTY_NONE_ENABLED` | 引导添加文案 |
| 空 | true | `EMPTY_NONE_DUE` | 今日不轮到告知文案 |

- `ALL_DONE` 判定沿用现逻辑（`items.filterNot { isDone }.isEmpty()`）。
- 状态为枚举或密封表达的落点由实现定（建议 `domain/model` 内纯类型 + 映射函数，`TodoRepositoryTest` 直测）。

## 3. `TodoRepository.todayTodos` 签名

```kotlin
// v8：fun todayTodos(today: String): List<TodayTodo>
fun todayTodos(today: String): TodayTodosSnapshot = synchronized(lock) {
    val due = overlayOrder(snapshot.filter { it.isEnabled && TodoRecurrence.isDue(...) })
        .map { TodayTodo(it.text, it.lastCompletedDate == today) }
    TodayTodosSnapshot(items = due, anyEnabled = snapshot.any { it.isEnabled })
}
```

- 过滤谓词、排序、`isDone` 口径零变化；唯一新增信息量是 `anyEnabled`。
- 调用点两处同步更新：`AppBlockerAccessibilityService`（快照直传覆盖层）、`TodoRepositoryTest`。

## 4. DataStore（`SettingsDataStore`）

| 项 | 处置 |
|---|---|
| `BuiltinHintsSetting` 接口 + 实现 | 删除 |
| `hintCursor: Flow<Int>` / `setHintCursor()` / `Keys.HINT_CURSOR` | 删除 |
| `Keys.BUILTIN_HINTS_ENABLED` 与读写 | 删除 |
| `AppSettings.builtinHintsEnabled` 字段 | 删除（连带 `SettingsViewModel` 默认值） |
| 设备上已写入的两键 | **残留不清理**（FR-007：MAY 保留；无读取者） |

## 5. 状态转换

- 覆盖层待办卡：四态在**构造时定格**（快照注入），存续期间不随勾选/跨日变化（005 契约延续，spec Edge Cases"空态卡布局不跳动"由此保证：前导 spacer 常驻 VISIBLE）。
- 底部导航：`待办 → 统计 → 设置` 三态静态枚举；持久化恢复失败兜底 `待办`（升级窗口旧存档 `"TIPS"`）。
