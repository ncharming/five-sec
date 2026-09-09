package com.fivesec.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.TargetApp

@Database(
    entities = [TargetApp::class, InterceptionEvent::class],
    version = 3, // v3：删除冗余的 app_statistics 汇总表（统计统一改为事件流水实时聚合）
    exportSchema = false,
)
@TypeConverters(InterceptionOutcomeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetAppDao(): TargetAppDao
    abstract fun interceptionEventDao(): InterceptionEventDao
}

// 从版本1迁移到版本2：添加 appName 字段和 AppStatistics 表
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 1. 添加 appName 列到 target_apps 表
        database.execSQL("ALTER TABLE target_apps ADD COLUMN appName TEXT NOT NULL DEFAULT ''")

        // 2. 更新现有记录的 appName（使用默认值，实际应该从 PackageManager 获取）
        database.execSQL("UPDATE target_apps SET appName = CASE packageName " +
            "WHEN 'com.ss.android.ugc.aweme' THEN '抖音' " +
            "WHEN 'com.xingin.xhs' THEN '小红书' " +
            "WHEN 'tv.danmaku.bili' THEN '哔哩哔哩' " +
            "ELSE packageName END")

        // 3. 创建 app_statistics 表
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS app_statistics (
                packageName TEXT PRIMARY KEY,
                totalInterceptions INTEGER NOT NULL DEFAULT 0,
                cancellations INTEGER NOT NULL DEFAULT 0,
                cancellationRate REAL NOT NULL DEFAULT 0.0,
                completedExercises INTEGER NOT NULL DEFAULT 0,
                lastUpdated INTEGER NOT NULL DEFAULT 0
            )
        """)
    }
}

// 从版本2迁移到版本3：删除冗余的按应用累计汇总表。
// 统计改为事件流水实时聚合；interception_events 数据不受影响。
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("DROP TABLE IF EXISTS app_statistics")
    }
}

class InterceptionOutcomeConverter {
    @TypeConverter
    fun toName(outcome: InterceptionOutcome): String = outcome.name

    @TypeConverter
    fun fromName(name: String): InterceptionOutcome = InterceptionOutcome.valueOf(name)
}
