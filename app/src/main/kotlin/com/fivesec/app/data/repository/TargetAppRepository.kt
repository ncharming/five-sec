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

    suspend fun upsert(app: TargetApp) = targetAppDao.upsert(app)

    suspend fun insertAll(apps: List<TargetApp>) = targetAppDao.insertAll(apps)

    suspend fun remove(packageName: String) = targetAppDao.delete(packageName)

    suspend fun setEnabled(packageName: String, enabled: Boolean) =
        targetAppDao.setEnabled(packageName, enabled)
}
