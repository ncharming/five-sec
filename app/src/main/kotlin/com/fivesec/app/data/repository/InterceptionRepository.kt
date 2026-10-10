package com.fivesec.app.data.repository

import com.fivesec.app.data.db.InterceptionEventDao
import com.fivesec.app.data.db.PackageRangeCount
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 拦截事件的写入与统计聚合（事件流水为唯一数据源，只增不删）。
 *
 * 今日抵制序号内存镜像（specs/011 拦截反馈三件套）：覆盖层状态机在主线程同步取
 * 「今日第 N 次抵制」，无法走 suspend 查询——与 TodoRepository.todayTodos 的内存快照
 * 同一架构先例。镜像恰好两个写入点（互斥锁内）：init 后台从事件表播种、每次点「取消」+1；
 * 事件本体在成功态展示结束后异步落库，镜像可能领先事件表最多一次（0.8s 窗口），
 * 进程重启后播种自动对齐——统计页抵制率走事件表实时聚合，两者长期一致、瞬时差 ≤1。
 */
@Singleton
class InterceptionRepository @Inject constructor(
    private val eventDao: InterceptionEventDao,
    private val timeProvider: TimeProvider,
) {
    private val mirrorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val resistLock = Any()
    private var resistedDateKey: String = "" // "yyyy-MM-dd"；空串 = 尚未播种/未使用
    private var resistedCount: Int = 0

    init {
        // 播种属尽力而为（与 FiveSecApp.ensureSeeded 同一防御口径，runCatching 静默）：
        // 生产上瞬时失败→镜像从 0 起算、进程重启播种自愈；进程/测试收尾时 db 可能已关，
        // 此时后台查询抛异常若不吞掉，Robolectric 会把非测试线程的未捕获异常归罪当前用例
        // （CI 慢机上 init 协程滑过 tearDown 的 db.close() 才执行——run #91 偶发失败根因）
        mirrorScope.launch { runCatching { reseedResistedMirror() } }
    }

    /** 记录一次拦截事件。事件表只增不删，所有统计均实时聚合。 */
    suspend fun record(event: InterceptionEvent) {
        eventDao.insert(event)
    }

    fun observeStats(startOfDay: Long): Flow<DayStats> {
        return combine(
            eventDao.observeTodayCount(startOfDay),
            eventDao.observeTodayCountByOutcome(startOfDay, InterceptionOutcome.CANCELED),
            eventDao.observeTodayCountByOutcome(startOfDay, InterceptionOutcome.OPENED),
        ) { total, canceled, opened ->
            DayStats(total = total, canceled = canceled, opened = opened)
        }
    }

    fun observeActiveDays(): Flow<List<String>> = eventDao.observeActiveDays()

    /** 周期内按应用聚合的拦截/打开/取消计数（供应用级历史统计卡片使用）。 */
    fun observeCountsByPackageBetween(rangeStart: Long, rangeEnd: Long): Flow<List<PackageRangeCount>> =
        eventDao.observeCountsByPackageBetween(rangeStart, rangeEnd)

    /** 最早拦截事件时间，用于推算年份筛选下界。 */
    fun observeEarliestTimestamp(): Flow<Long?> = eventDao.observeEarliestTimestamp()

    /** 周期内全部事件时间戳升序（specs/011 时段分布数据源），分桶见 [com.fivesec.app.util.HourDistribution]。 */
    fun observeTimestampsBetween(rangeStart: Long, rangeEnd: Long): Flow<List<Long>> =
        eventDao.observeTimestampsBetween(rangeStart, rangeEnd)

    /**
     * 从事件表播种今日抵制镜像（进程重建后恢复计数）：同日取 max(现值, 事件表计数)——
     * 播种完成前用户可能已抵制过（镜像领先落库）；未使用（key 空）直接置入；
     * 跨日/时钟回拨保留现值（镜像只认当天，不为旧日期翻旧账）。
     */
    suspend fun reseedResistedMirror(now: Long = timeProvider.now()) {
        val today = DateUtil.todayString(now)
        val dbCount = eventDao.countByOutcomeSince(DateUtil.startOfDayMillis(now), InterceptionOutcome.CANCELED)
        synchronized(resistLock) {
            when {
                resistedDateKey == today -> resistedCount = maxOf(resistedCount, dbCount)
                resistedDateKey.isEmpty() -> {
                    resistedDateKey = today
                    resistedCount = dbCount
                }
                // 已是别的日期：正常翻日（nextResistedOrdinal 会自行归零）或时钟回拨，保留现值
            }
        }
    }

    /** 覆盖层成功态同步读取（主线程安全）：今日抵制序号（含本次，先记账后展示）；
     *  日期串不等先归零——跨零点后第一次抵制重新从 1 起。 */
    fun nextResistedOrdinal(today: String): Int = synchronized(resistLock) {
        if (resistedDateKey != today) {
            resistedDateKey = today
            resistedCount = 0
        }
        resistedCount += 1
        resistedCount
    }

    data class DayStats(val total: Int, val canceled: Int, val opened: Int)
}
