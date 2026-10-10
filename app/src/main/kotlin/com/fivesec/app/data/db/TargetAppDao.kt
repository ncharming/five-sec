package com.fivesec.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fivesec.app.domain.model.TargetApp
import kotlinx.coroutines.flow.Flow

@Dao
interface TargetAppDao {
    @Query("SELECT * FROM target_apps ORDER BY isDefault DESC, addedAt ASC")
    fun observeAll(): Flow<List<TargetApp>>

    @Query("SELECT COUNT(*) FROM target_apps")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(app: TargetApp)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(apps: List<TargetApp>)

    @Query("DELETE FROM target_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("UPDATE target_apps SET isEnabled = :enabled WHERE packageName = :packageName")
    suspend fun setEnabled(packageName: String, enabled: Boolean)

    // 每应用会话守护开关（specs/012）：关=该应用会话永不回弹（会话记录照写）
    @Query("UPDATE target_apps SET sessionGuardEnabled = :enabled WHERE packageName = :packageName")
    suspend fun setSessionGuardEnabled(packageName: String, enabled: Boolean)
}
