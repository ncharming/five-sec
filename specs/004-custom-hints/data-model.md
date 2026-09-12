# Data Model: 拦截页自定义提示语（栈式一次性提示 + 自定义提示语池）

**Date**: 2026-09-12 | **Spec**: [spec.md](spec.md)

## 实体与表

### 1. `hints`（新增，v4）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Long PK 自增 | 入库序；栈序即 id 序（栈顶 = MAX(id)） |
| `text` | String | 提示语文本（≤30 字符，非空白，写入前由调用方校验） |
| `kind` | String | `stack`（拦截页保存的一次性提示）/ `pool`（自定义提示语池） |

```kotlin
// domain/model/Hint.kt
@Entity(tableName = "hints")
data class Hint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val kind: String,
)

object HintKind {
    const val STACK = "stack"
    const val POOL = "pool"
}
```

- kind 用字符串常量而非枚举转换器：仅两值、无业务多态，避免为一张小表引入 TypeConverter（与 `InterceptionOutcome` 转换器并存的复杂度不成比例）。
- 无索引：个人级数据量（数十行）全表扫描即返回（YAGNI）。
- 允许重复文本（spec Assumptions），无唯一约束。

### 2. 既有表（零变更）

`interception_events`、`target_apps` 不动；本特性不触碰拦截事件记录。

## 数据库版本

| 版本 | 变更 | 迁移 |
|---|---|---|
| v3（现状） | — | — |
| **v4（本特性）** | 新增 `hints` 表 | `MIGRATION_3_4`: `CREATE TABLE IF NOT EXISTS hints (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, text TEXT NOT NULL, kind TEXT NOT NULL)` |

```kotlin
// AppDatabase.kt
@Database(
    entities = [TargetApp::class, InterceptionEvent::class, Hint::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetAppDao(): TargetAppDao
    abstract fun interceptionEventDao(): InterceptionEventDao
    abstract fun hintDao(): HintDao   // 新增
}
```

**红线**：禁止 `fallbackToDestructiveMigration`（沿用 003 数据留存红线，覆盖新表）。

## 新增类型

### `HintRepository`（快照 + 同步消费，非持久化实体）

| 成员 | 说明 |
|---|---|
| `takeNextHint(builtin: List<String>): String` | 主线程同步：栈非空 → 弹栈顶（内存同步 + 异步删库 + pending 防复活）；栈空 → `(builtin + pool).random()` |
| `pushStackHint(text: String)` | 拦截页保存 → `insert(kind=stack)` |
| `addPoolHint(text: String)` | 管理页添加 → `insert(kind=pool)` |
| `removePoolHint(id: Long)` | 管理页删除 → `deleteById(id)` |
| `observePool(): Flow<List<Hint>>` | 管理页列表（透传 DAO） |

内存态：`stackSnapshot: List<Hint>`（id 升序）、`poolSnapshot: List<String>`、`consumedPending: MutableSet<Long>`——均由同一锁保护。

## 数据流

```text
拦截页保存    → HintRepository.pushStackHint → INSERT hints(kind=stack)
                                                 ↓ Flow
拦截触发(主线程) → HintRepository.takeNextHint：快照弹栈顶 + 异步 DELETE
                 ├─ 栈非空：展示弹出的文本（一次性消费）
                 └─ 栈空：(内置7条 + pool快照).random()
管理页        → observePool/addPoolHint/removePoolHint → hints(kind=pool)
```

## 验证规则（来自需求）

- LIFO：先存 A 后存 B，连续拦截依次展示 B、A，之后回落随机池（SC-001，Repository 测试）。
- 展示即消费：任意结果（打开/取消/中断）后栈顶不复活（SC-001 场景 5，防复活测试）。
- 持久化：进程重启后栈/池恢复（SC-002，DAO + 迁移测试 + quickstart 手测）。
- 合并池：池条目与内置共同参与随机（SC-003，Repository 测试穷举验证）。
- 30 字 / 空白：写入侧校验拒绝（FR-001/FR-010，ViewModel 测试；覆盖层侧 LengthFilter + trim 校验）。
