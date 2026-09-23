package com.fivesec.app.data.repository

import androidx.annotation.VisibleForTesting
import com.fivesec.app.data.datastore.BuiltinHintsSetting
import com.fivesec.app.data.db.HintDao
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.HintKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 提示语聚合点（specs/005-daily-todos 循环展示；004 的栈式机制已随拦截页输入入口退役）：
 *  - 无障碍服务在主线程同步调 [takeNextHint] 决定覆盖层展示文本（快照模式，对齐 InterceptionController）；
 *  - 管理页经 [observePool]/[addPoolHint]/[removePoolHint] 维护自定义提示语池；
 *  - 内置提示语开关（[BuiltinHintsSetting]，默认开启）控制内置条目是否参与循环序列，
 *    关闭 = 仅自定义池参与轮换（池亦空 → 空串，不兜底内置）。
 *
 * 循环语义（共识确认）：序列 = 内置提示语（资源数组顺序，受开关过滤）+ 池（id 升序），
 * 每次拦截取 `sequence[cursor % size]` 并推进游标；游标经 [HintCursorStore] 持久化，
 * 进程重启后续接；序列增删后取模自然延续，不承诺轮转严格不重不漏。池条目常驻，取值不删行。
 */
@Singleton
class HintRepository @Inject constructor(
    private val hintDao: HintDao,
    private val cursorStore: HintCursorStore,
    builtinHintsSetting: BuiltinHintsSetting,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Any()
    private var poolSnapshot: List<String> = emptyList() // id 升序 = 添加顺序
    private var cursor: Int = 0 // 下一次应展示的序列下标（持久化值 +1 口径，读时取模）

    // 内置开关快照：后台协程收集 DataStore 流，takeNextHint 只读（对齐 globalEnabled 收集模式）
    @Volatile private var builtinEnabled = true

    /** 游标落库收敛队列：只保留最新待写值，单一收集器顺序落库——连续拦截不会因协程乱序回退。 */
    private val pendingCursorWrite = MutableStateFlow<Int?>(null)

    init {
        scope.launch {
            builtinHintsSetting.builtinHintsEnabled.collect { builtinEnabled = it }
        }
        scope.launch {
            hintDao.observeByKind(HintKind.POOL).collect { entries ->
                synchronized(lock) { poolSnapshot = entries.map { it.text } }
            }
        }
        // 游标只做一次性首读（进程重启续接场景）：运行期以内存 cursor 为唯一事实源。
        // 不做常驻回读——持久化回显天然滞后于内存推进，迟到的回显会把游标重置回旧值（重复提示语）。
        scope.launch {
            val persisted = cursorStore.observeCursor().first()
            synchronized(lock) { if (cursor == 0) cursor = persisted }
        }
        scope.launch {
            pendingCursorWrite.filterNotNull().collect { value ->
                runCatching { cursorStore.writeCursor(value) } // 持久化失败不拖垮采集协程
            }
        }
    }

    /**
     * 覆盖层创建时调用（主线程安全：锁内纯内存取模与推进，落库走收敛队列）。
     * builtin 恒非空（资源数组契约）但可能被开关整体过滤；序列意外为空时返回空串兜底，不抛异常。
     */
    fun takeNextHint(builtinHints: List<String>): String {
        val text: String
        synchronized(lock) {
            val builtins = if (builtinEnabled) builtinHints else emptyList()
            val sequence = builtins + poolSnapshot
            if (sequence.isEmpty()) return ""
            val index = ((cursor % sequence.size) + sequence.size) % sequence.size
            text = sequence[index]
            cursor = index + 1
            pendingCursorWrite.value = cursor // 锁内入队：落库值与推进值严格同源，不读跨锁旧值
        }
        return text
    }

    /** 管理页：添加自定义提示语（并入循环序列尾部）。 */
    fun addPoolHint(text: String) {
        val normalized = normalize(text) ?: return
        scope.launch { hintDao.insert(Hint(text = normalized, kind = HintKind.POOL)) }
    }

    /** 管理页：删除指定池条目。 */
    fun removePoolHint(id: Long) {
        scope.launch { hintDao.deleteById(id) }
    }

    /** 管理页列表（id 升序 = 添加顺序）。 */
    fun observePool(): Flow<List<Hint>> = hintDao.observeByKind(HintKind.POOL)

    /** 统一入口校验：trim 非空白 + 30 字符硬截断；不合法返回 null（不落库）。 */
    private fun normalize(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        return trimmed.take(MAX_HINT_LENGTH)
    }

    /** 仅测试观测：当前游标值，用于等待 observe 重发收敛；生产不调用。 */
    @VisibleForTesting
    fun cursorForTest(): Int = synchronized(lock) { cursor }

    companion object {
        const val MAX_HINT_LENGTH = 30
    }
}

/** 循环游标持久化端口：DataStore 实现（DataStoreHintCursorStore）+ 测试 fake（纯 JVM）。 */
interface HintCursorStore {
    fun observeCursor(): Flow<Int>
    suspend fun writeCursor(value: Int)
}
