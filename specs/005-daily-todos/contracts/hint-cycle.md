# Contract: 提示语循环展示与游标持久化

**Date**: 2026-09-23 | **Spec**: [spec.md](spec.md)

## 语义（共识确认）

1. **序列**：`builtinHints`（服务传入的资源数组顺序）+ `poolSnapshot`（id 升序 = 添加顺序），合成单一序列。
2. **推进**：每次 `takeNextHint` 取 `sequence[cursor % sequence.size]`，随后游标推进。
3. **持久化**：游标存 DataStore（`hint_cycle_cursor`）；进程重启后从上次位置续接，不归零。
4. **增删取模**：序列变化后对当前长度取模继续；不承诺轮转严格不重不漏。
5. **无消费删除**：池条目常驻，`takeNextHint` 不删任何行（"展示即消费"随栈退役）。
6. **管理页零行为变更**：查看/添加/删除照旧；仅"随机"文案改"循环"口径。

## API 变更

```kotlin
class HintRepository @Inject constructor(
    private val hintDao: HintDao,
    private val cursorStore: HintCursorStore,   // 新增
) {
    /** 循环取下一条：锁内读快照定 index，游标推进经收敛队列异步落库。 */
    fun takeNextHint(builtinHints: List<String>): String

    fun addPoolHint(text: String)      // 保留（校验口径不变）
    fun removePoolHint(id: Long)       // 保留
    fun observePool(): Flow<List<Hint>> // 保留

    // 删除：pushStackHint / stackSnapshotSizeForTest / 栈快照 / consumedPending
}

fun interface HintCursorStore {
    fun observeCursor(): Flow<Int>
    suspend fun writeCursor(value: Int)
}
```

## 不变量

1. **主线程同步**：`takeNextHint` 锁内纯内存（读快照 + 取模 + 推进变量），落库走收敛队列（research R3）。
2. **写收敛**：游标落库经 `MutableStateFlow<Int?>` 单一收集器，只持久化最新值——连续拦截不会因协程乱序导致游标回退。
3. **空序列防御**：`builtinHints` 契约上恒非空（资源数组）；若序列意外为空返回空串，不抛异常。
4. **游标有界**：持久化值为 `index + 1`（小非负整数）；读取时取模兜底越界。

## 迁移契约（MIGRATION_4_5 后半）

`UPDATE hints SET kind = 'pool' WHERE kind = 'stack'`——执行后 `observeByKind(POOL)` 覆盖全部历史文本；无 `stack` 残留行。

## 测试锚点（HintRepositoryTest 重写，纯 JVM）

- `循环展示按内置加池顺序轮转且末尾回环`（builtin [A,B] + pool [P] → A,B,P,A）
- `池快照就绪前后序列自然过渡`（await 模式对齐既有测试）
- `游标跨实例续接`（新 Repository 挂同一 fake store，从上次位置继续）
- `序列缩短后游标取模不越界`
- `addPoolHint校验转发`（空白拒/截断）
- 删除全部栈用例（LIFO/防复活/pushStackHint）
