package com.fivesec.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.fivesec.app.domain.model.AppStatistics
import kotlinx.coroutines.flow.Flow

@Dao
interface AppStatisticsDao {
    @Query("SELECT * FROM app_statistics ORDER BY totalInterceptions DESC")
    fun observeAll(): Flow<List<AppStatistics>>

    @Query("SELECT * FROM app_statistics WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): AppStatistics?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(statistics: AppStatistics)

    @Query("UPDATE app_statistics SET totalInterceptions = totalInterceptions + 1 WHERE packageName = :packageName")
    suspend fun incrementTotalInterceptions(packageName: String)

    @Query("UPDATE app_statistics SET cancellations = cancellations + 1 WHERE packageName = :packageName")
    suspend fun incrementCancellations(packageName: String)

    @Query("UPDATE app_statistics SET completedExercises = completedExercises + 1 WHERE packageName = :packageName")
    suspend fun incrementCompletedExercises(packageName: String)
}