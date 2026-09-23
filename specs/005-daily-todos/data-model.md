# Data Model: 每日待办与拦截流程打通

**Date**: 2026-09-23 | **Spec**: [spec.md](spec.md)

## 实体与表

### 1. `todos`（新增，v5）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK 自增 | 创建顺序即展示顺序（id 升序） |
| `text` | String | 待办标题（≤30 字符，trim 后非空白，写入前由调用方校验） |
| `isEnabled` | Boolean | 启用开关；停用条目不进覆盖层快照、不计入进度分母 |
| `lastCompletedDate` | String | 最近完成日（`yyyy-MM-dd`，`DateUtil.todayString` 口径）；空串 = 从未完成。**完成判定 = 该值 == 今天**，跨日惰性重置 |

```kotlin
// domain/model/Todo.kt
@Entity(tableName = "todos")
data class Todo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val isEnabled: Boolean = true,
    val lastCompletedDate: String = "",
)

/** 覆盖层快照行（纯 Kotlin 值类型，服务侧映射产出）。 */
data class TodayTodo(
    val text: String,
    val isDone: Boolean,
)
```

- 无外键、无索引：个人级数据量（≤20 行）全表扫描即返回（YAGNI）。
- 允许重复文本；不做完成历史（v1 无统计诉求，见 research R1）。
- `TodayTodo` 放在 `domain/model` 且零 Android import（`domain.model` 层约束）。

### 2. `hints`（既有，语义收敛）

表结构零变更（`kind` 列保留）；`kind` 取值收敛为仅 `pool`：

- `stack` **退役**：生产者（拦截页输入行）随本特性移除；
- 存量迁移：`UPDATE hints SET kind = 'pool' WHERE kind = 'stack'`（用户文字零丢失，并入循环序列）；
- `HintKind.STACK` 常量删除，仅存 `POOL`。

### 3. 既有表（零变更）

`interception_events`、`target_apps` 不动（红线）。

## 数据库版本

| 版本 | 变更 | 迁移 |
|---|---|---|
| v4（现状） | — | — |
| **v5（本特性）** | 新增 `todos` 表；`hints` 存量 stack 行改挂 pool | `MIGRATION_4_5`（见下） |

```kotlin
// AppDatabase.kt
@Database(
    entities = [TargetApp::class, InterceptionEvent::class, Hint::class, Todo::class],
    version = 5,
    exportSchema = false,
)
```

```kotlin
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 列定义必须与 Room 为 Todo 实体生成的 schema 逐字一致
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `todos` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`isEnabled` INTEGER NOT NULL, " +
                "`lastCompletedDate` TEXT NOT NULL)"
        )
        // 栈式一次性提示随拦截页输入入口退役：改挂为池，用户文字零丢失
        database.execSQL("UPDATE hints SET kind = 'pool' WHERE kind = 'stack'")
    }
}
```

**红线**：禁止 `fallbackToDestructiveMigration`；`interception_events` 零触碰。

## DataStore 新键

| 键 | 类型 | 说明 |
|---|---|---|
| `hint_cycle_cursor` | Int | 提示语循环游标：下一次应展示的序列下标；读侧 Flow 收集进快照，写侧经 StateFlow 收敛后落库（research R3/R4） |

## 新增类型

### `TodoRepository`（快照 + 同步读，写路径 suspend）

| 成员 | 说明 |
|---|---|
| `todayTodos(today: String): List<TodayTodo>` | 主线程同步：启用条目映射为快照行（`isDone = lastCompletedDate == today`），id 升序 |
| `observeAll(): Flow<List<Todo>>` | 待办页列表（透传 DAO，id 升序） |
| `add(text: String): Result<Unit>` | 校验（trim/空白拒/30 字截断）+ 上限 20 → insert |
| `rename(id: Long, text: String): Result<Unit>` | 同一校验 → 定向 UPDATE text |
| `remove(id: Long)` | 删除 |
| `setEnabled(id: Long, enabled: Boolean)` | 定向 UPDATE isEnabled |
| `setCompleted(id: Long, today: String, completed: Boolean)` | 勾选写 `today`、取消写 `""`（定向 UPDATE lastCompletedDate） |

内存态：`snapshot: List<Todo>`（id 升序，同一锁保护），由后台协程收集 DAO Flow 维护。

### `HintCursorStore`（游标持久化端口）

```kotlin
fun interface HintCursorStore {
    fun observeCursor(): Flow<Int>
    suspend fun writeCursor(value: Int)
}
```

生产实现 `DataStoreHintCursorStore`（包装 `SettingsDataStore` 新键）；端口化使 `HintRepositoryTest` 保持纯 JVM（fake StateFlow 即可，无需 Robolectric）。

## 数据流

```text
待办页勾选     → TodoViewModel.setCompleted(id, done) → UPDATE todos.lastCompletedDate = today|""
                                                          ↓ Flow
拦截触发(主线程) → TodoRepository.todayTodos(todayString)：启用条目 → 快照行 → BlockingOverlay 紧凑卡片
                  （完成态按 lastCompletedDate == today 惰性求值，跨日自动重置，无清理任务）

提示语循环      → takeNextHint(builtin)：[builtin + poolSnapshot][cursor % size] → 推进游标 → DataStore 收敛落库
管理页增删池    → hints(kind=pool) Flow → poolSnapshot → 下一次取模自然延续
```

## 验证规则（来自需求）

- 每日重置：昨日勾选今日视为未完成（`lastCompletedDate` 字符串不等，SC-002，Repository 测试）。
- 快照过滤：停用条目不进 `todayTodos`、不计分母（SC-003，Repository 测试）。
- 上限与校验：第 21 条拒绝、空白拒、30 字截断（SC-004，Repository/ViewModel 测试）。
- 循环：序列顺序推进、末尾回环、跨实例续接、池增删取模（SC-005，HintRepository 测试）。
- 迁移：v4 手建库（含 stack 行）→ v5 打开 → todos 可用 + stack 全部变 pool（SC-005，迁移测试）。
