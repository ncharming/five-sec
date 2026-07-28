package com.fivesec.app.di

import android.content.Context
import androidx.room.Room
import com.fivesec.app.data.db.AppDatabase
import com.fivesec.app.data.db.InterceptionEventDao
import com.fivesec.app.data.db.TargetAppDao
import com.fivesec.app.util.SystemTimeProvider
import com.fivesec.app.util.TimeProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "five_sec.db").build()

    @Provides
    fun provideTargetAppDao(db: AppDatabase): TargetAppDao = db.targetAppDao()

    @Provides
    fun provideInterceptionEventDao(db: AppDatabase): InterceptionEventDao =
        db.interceptionEventDao()

    @Provides
    fun provideTimeProvider(): TimeProvider = SystemTimeProvider()

    /** 应用级作用域：用于不随 Activity 销毁的 fire-and-forget 操作（如写入拦截日志）。 */
    @Provides
    @Singleton
    fun provideAppCoroutineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
