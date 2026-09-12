# Contract: 提示语存储与消费层（HintDao / HintRepository）

**Date**: 2026-09-12 | **Consumers**: `AppBlockerAccessibilityService`、`HintListViewModel`、`BlockingOverlay`（经回调间接触发）、存储层测试

## HintDao — Room 接口

```kotlin
@Dao
interface HintDao {
    @Insert
    suspend fun insert(hint: Hint): Long

    @Query("SELECT * FROM hints WHERE kind = :kind ORDER BY id ASC")
    fun observeByKind(kind: String): Flow<List<Hint>>

    @Query("DELETE FROM hints WHERE id = :id")
    suspend fun deleteById(id: Long)
}
```

**约定**：
- `observeByKind` 返回 **id 升序**；栈顶 = 列表最后一个元素；`stack` / `pool` 用 `HintKind` 常量传参。
- 无匹配行返回空 List（非 null）；表变更自动重发。
- 接口保持最小三方法——`HintRepositoryTest` 手写 fake 直接实现。

## HintRepository — 快照 + 同步消费

```kotlin
@Singleton
class HintRepository @Inject constructor(private val hintDao: HintDao) {

    /** 覆盖层创建时调用（主线程，同步、零 IO）。
     *  栈非空：返回栈顶文本并消费（内存弹栈 + 异步删库 + pending 防复活）；
     *  栈空：返回 (builtin + 自定义池).random()，不消费任何数据。 */
    fun takeNextHint(builtinHints: List<String>): String

    /** 拦截页"保存"：入栈（text 已由调用方 trim 且非空白、≤30 字符）。 */
    fun pushStackHint(text: String)

    /** 管理页：添加自定义提示语（并入随机池）。校验同上。 */
    fun addPoolHint(text: String)

    /** 管理页：删除指定池条目。 */
    fun removePoolHint(id: Long)

    /** 管理页列表（id 升序 = 添加顺序）。 */
    fun observePool(): Flow<List<Hint>>

    companion object { const val MAX_HINT_LENGTH = 30 }
}
```

**不变式**：
- `takeNextHint` 可在主线程安全调用：锁内纯内存操作，删库走自有 scope（`SupervisorJob + Dispatchers.Default`）异步。
- **一次性消费保证**：`consumedPending` 集合记录"已内存弹出、待删库确认"的 id；DAO observe 重发时 `filter { it.id !in consumedPending }`，并以最新列表 `retainAll` 收敛。同一栈顶至多被 `takeNextHint` 返回一次。
- 文本校验（trim 非空白、`MAX_HINT_LENGTH` 截断）在 **Repository 入口统一执行**（`pushStackHint` / `addPoolHint` 双入口同一私有函数），UI 层 LengthFilter 只是第一道防线。
- `builtinHints` 由调用方从资源注入，Repository 不读资源（保持纯数据层可测）。
- 快照未就绪（进程刚起）时 `takeNextHint` 视栈为空 → 回落随机池，不阻塞不报错。

## 接线 — AppBlockerAccessibilityService

```kotlin
@EntryPoint
@InstallIn(SingletonComponent::class)
interface InterceptionEntryPoint {
    fun controller(): InterceptionController
    fun repository(): InterceptionRepository
    fun hintRepository(): HintRepository   // 新增
    fun timeProvider(): TimeProvider
    fun appScope(): CoroutineScope
}

// onAccessibilityEvent 命中分支：
val builtin = resources.getStringArray(R.array.blocking_exercise_hints).toList()
val hintText = hintRepository.takeNextHint(builtin)
val overlay = BlockingOverlay(
    context = this,
    appLabel = appLabel,
    hintText = hintText,                                    // 展示文本（栈顶或随机）
    onSaveHint = { hintRepository.pushStackHint(it) },       // 输入保存回调
    onFinished = { outcome -> onBlockingFinished(pkg, outcome) },
)
```

**约定**：`takeNextHint` 在创建 overlay 前调用一次且仅一次（消费点 = 展示点，FR-003）；覆盖层显示期间后续事件被忽略（现状），无二次消费窗口。

## 数据库 — AppDatabase v4

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS hints (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                text TEXT NOT NULL,
                kind TEXT NOT NULL
            )
        """)
    }
}
// provideHintDao(db: AppDatabase): HintDao = db.hintDao()
```

**红线**：不触碰既有两表；禁止破坏性迁移。
