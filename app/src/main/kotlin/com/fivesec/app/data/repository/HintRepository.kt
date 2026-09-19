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
import kotlinx.coroutines.launch

/**
 * 提示语聚合点（specs/004-custom-hints）：
 *  - 无障碍服务在主线程同步调 [takeNextHint] 决定覆盖层展示文本（快照模式，对齐 InterceptionController）；
 *  - 管理页经 [observePool]/[addPoolHint]/[removePoolHint] 维护自定义提示语池；
 *  - 拦截页输入经 [pushStackHint] 入栈；
 *  - 内置提示语开关（[BuiltinHintsSetting]，默认开启）只控制内置条目是否参与栈空时的随机回落，
 *    不影响栈式一次性提示与自定义池。
 *
 * 一次性消费不变式：同一栈顶至多被 takeNextHint 返回一次——内存弹栈后立即标记 consumedPending，
 * observe 在删库落库前重发时按该集合过滤（防"栈顶复活"），删库确认后随最新列表收敛。
 */
@Singleton
class HintRepository @Inject constructor(
    private val hintDao: HintDao,
    builtinHintsSetting: BuiltinHintsSetting,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Any()
    private var stackSnapshot: List<Hint> = emptyList() // id 升序，栈顶 = last()
    private var poolSnapshot: List<String> = emptyList()

    // 内置开关快照：后台协程收集 DataStore 流，主线程 takeNextHint 只读 volatile（对齐 globalEnabled 收集模式）
    @Volatile private var builtinEnabled = true

    private val consumedPending = mutableSetOf<Long>() // 已内存弹出、待删库确认的栈顶 id

    init {
        scope.launch {
            builtinHintsSetting.builtinHintsEnabled.collect { builtinEnabled = it }
        }
        scope.launch {
            hintDao.observeByKind(HintKind.STACK).collect { entries ->
                synchronized(lock) {
                    consumedPending.retainAll(entries.map { it.id }.toSet()) // 删库确认后收敛
                    stackSnapshot = entries.filter { it.id !in consumedPending }
                }
            }
        }
        scope.launch {
            hintDao.observeByKind(HintKind.POOL).collect { entries ->
                synchronized(lock) { poolSnapshot = entries.map { it.text } }
            }
        }
    }

    /**
     * 覆盖层创建时调用（主线程安全：锁内纯内存操作，删库走后台协程）。
     * 栈非空 → 返回栈顶文本并消费（栈与内置开关无关）；栈空 → 从候选随机：
     * 开关开启 = (内置 + 自定义池)，关闭 = 仅自定义池（池亦空 → 空串，无提示语可展示）。
     */
    fun takeNextHint(builtinHints: List<String>): String {
        val top = synchronized(lock) {
            val entry = stackSnapshot.lastOrNull() ?: return@synchronized null
            consumedPending += entry.id
            stackSnapshot = stackSnapshot.dropLast(1)
            entry
        }
        if (top != null) {
            val id = top.id
            scope.launch { hintDao.deleteById(id) } // 异步落库；observe 重发已被 pending 过滤
            return top.text
        }
        val pool = synchronized(lock) { poolSnapshot.toList() }
        // 内置开关关闭：内置条目退出随机，候选仅剩自定义池；两边皆空时返回空串（不再兜底内置）
        val candidates = if (builtinEnabled) builtinHints + pool else pool
        return candidates.randomOrNull().orEmpty()
    }

    /** 拦截页"保存"：入栈（一次性提示，下次拦截优先展示）。 */
    fun pushStackHint(text: String) {
        val normalized = normalize(text) ?: return
        scope.launch { hintDao.insert(Hint(text = normalized, kind = HintKind.STACK)) }
    }

    /** 管理页：添加自定义提示语（并入随机池）。 */
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

    /** 仅测试观测：栈快照当前条数，用于等待 observe 重发收敛；生产不调用。 */
    @VisibleForTesting
    fun stackSnapshotSizeForTest(): Int = synchronized(lock) { stackSnapshot.size }

    companion object {
        const val MAX_HINT_LENGTH = 30
    }
}
