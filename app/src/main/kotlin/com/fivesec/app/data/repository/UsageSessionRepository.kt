package com.fivesec.app.data.repository

import com.fivesec.app.data.db.PackageDurationRow
import com.fivesec.app.data.db.UsageSessionDao
import com.fivesec.app.domain.model.UsageSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 会话事件存取口（specs/012）：`usage_sessions` 的唯一写入点（结束时单行 INSERT），
 * 与 InterceptionRepository 分置——interception_events 的单写入点契约不掺入会话写入。
 */
@Singleton
class UsageSessionRepository @Inject constructor(
    private val usageSessionDao: UsageSessionDao,
) {
    suspend fun record(session: UsageSession) = usageSessionDao.insert(session)

    fun observeDurationByPackageBetween(startMillis: Long, endMillis: Long): Flow<List<PackageDurationRow>> =
        usageSessionDao.observeDurationByPackageBetween(startMillis, endMillis)

    /** 今日已落库会话的停留总毫秒（specs/015 预算：endedAt ≥ startOfDay，与统计归期同口径）。 */
    suspend fun todayStayMillisBefore(packageName: String, startOfDay: Long): Long =
        usageSessionDao.sumDurationSince(packageName, startOfDay)

    /** 全量快照升序（specs/017 导出只读）。 */
    suspend fun exportSnapshot(): List<UsageSession> = usageSessionDao.getAllOrdered()
}
