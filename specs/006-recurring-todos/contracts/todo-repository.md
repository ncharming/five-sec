# Contract: 数据层（DAO / Repository / 迁移）

## TodoDao（新增方法）

```kotlin
/** 定向更新重复规则三列；不触碰 text/isEnabled/lastCompletedDate（不覆盖并发勾选）。 */
@Query("UPDATE todos SET repeatType = :repeatType, repeatDays = :repeatDays, intervalDays = :intervalDays WHERE id = :id")
suspend fun updateRecurrence(id: Long, repeatType: Int, repeatDays: Int, intervalDays: Int)
```

既有方法（observeAll / insert / updateText / setEnabled / setCompletedDate / deleteById / count）签名与语义零变化。

## TodoRepository

```kotlin
/** 新建带规则；rule 缺省 = 每天（既有调用零改动）。校验失败返回 Result.failure 中文文案。 */
suspend fun add(text: String, rule: TodoRule = TodoRule.DAILY): Result<Unit>

/** 修改重复规则；同校验口径；定向 UPDATE 不触碰并发字段。 */
suspend fun setRecurrence(id: Long, rule: TodoRule): Result<Unit>
```

- **TodoRule 值类型**（domain/model，纯 Kotlin）：`(type, repeatDays, intervalDays)` 三元组 + 工厂 `daily()` / `weekly(days: Set<DayOfWeek>)` / `interval(n: Int)`；`weekly` 空集抛校验错误、`interval` 越界 `coerceIn(2..365)` 收敛。
- **校验口径**（Repository 兜底，主拦截在 UI 表单层）：
  - `rule.type == WEEKLY && rule.repeatDays == 0` → `Result.failure("每周至少选择一天")`
  - `rule.type == INTERVAL` → `intervalDays` 收敛到 2..365
- **todayTodos(today)**：过滤条件 `isEnabled` → `isEnabled && TodoRecurrence.isDue(...)`（快照锁内纯内存映射，主线程同步语义不变）。分母/分子语义随之自动正确：返回列表长度 = D；其中 `isDone` 计数 = T 分子口径由覆盖层既有逻辑消费。
- `observeAll` 透传不变（待办页列表展示全部条目，含不轮到的——灰显由 UI 层依据 `dueToday` 呈现，不在 repo 过滤）。

## AppDatabase / AppModule

- `AppDatabase` version `5 → 6`；`MIGRATION_5_6` = 3× `ALTER TABLE todos ADD COLUMN <col> INTEGER NOT NULL DEFAULT 0`（列定义逐字对齐 Room schema，见 data-model.md）。
- `AppModule`：`MIGRATIONS` 数组追加 `MIGRATION_5_6`；无新 DAO、无新绑定。
- 服务侧（`AppBlockerAccessibilityService` EntryPoint 与 `todayTodos` 调用）**零改动**。

## 迁移回归测试（AppDatabaseMigrationTest 新用例）

1. 手建 v5 库：`todos(id, text, isEnabled, lastCompletedDate)` 四列 schema，插入启用已勾选 / 启用未勾选 / 停用 三行。
2. 以 v6 打开（注册全链 `MIGRATION_1_2..5_6`）。
3. 断言：三行全部保留、字段值不变；`repeatType / repeatDays / intervalDays` 均为 0。
4. 断言 v6 打开后 `todoDao` 可正常 insert/observe（新列可写）。
