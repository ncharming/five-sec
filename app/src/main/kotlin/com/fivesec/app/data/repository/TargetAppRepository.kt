package com.fivesec.app.data.repository

import com.fivesec.app.data.db.TargetAppDao
import com.fivesec.app.domain.model.TargetApp
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class TargetAppRepository @Inject constructor(
    private val targetAppDao: TargetAppDao,
) {
    fun observeAll(): Flow<List<TargetApp>> = targetAppDao.observeAll()

    suspend fun count(): Int = targetAppDao.count()

    /**
     * 检查是否可以添加新应用（3个应用限制）
     * @return true 如果可以添加，false 如果已达到上限
     */
    suspend fun canAddNewApp(): Boolean = count() < 3

    /**
     * 添加新应用，强制执行3个应用限制
     * @return Result<Unit> 成功或失败原因
     */
    suspend fun addNewApp(app: TargetApp): Result<Unit> {
        if (!canAddNewApp()) {
            return Result.failure(IllegalStateException("最多可添加3个应用，请移除不常用应用后重试"))
        }
        return try {
            upsert(app)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun upsert(app: TargetApp) = targetAppDao.upsert(app)

    suspend fun insertAll(apps: List<TargetApp>) = targetAppDao.insertAll(apps)

    suspend fun remove(packageName: String) = targetAppDao.delete(packageName)

    suspend fun setEnabled(packageName: String, enabled: Boolean) =
        targetAppDao.setEnabled(packageName, enabled)
}
