package com.fivesec.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.TargetApp
import com.fivesec.app.domain.model.Todo

@Database(
    entities = [TargetApp::class, InterceptionEvent::class, Hint::class, Todo::class],
    version = 5, // v5：新增 todos 表（每日待办）+ hints 存量 stack 行改挂 pool（specs/005-daily-todos）
    exportSchema = false,
)
@TypeConverters(InterceptionOutcomeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetAppDao(): TargetAppDao
    abstract fun interceptionEventDao(): InterceptionEventDao
    abstract fun hintDao(): HintDao
    abstract fun todoDao(): TodoDao
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

// 从版本3迁移到版本4：新增 hints 表（拦截页自定义提示语）。
// 既有两表零触碰。
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS hints (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "text TEXT NOT NULL, " +
                "kind TEXT NOT NULL)"
        )
    }
}

// 从版本4迁移到版本5：新增 todos 表（每日待办，specs/005-daily-todos）；
// 同时把 hints 存量 stack 行改挂为 pool——拦截页输入入口退役后，用户留的话并入循环序列零丢失。
// 既有三表结构零触碰（interception_events 红线）。
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // 列定义必须与 Room 为 Todo 实体生成的 schema 逐字一致（AppDatabaseMigrationTest 守住）
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `todos` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`text` TEXT NOT NULL, " +
                "`isEnabled` INTEGER NOT NULL, " +
                "`lastCompletedDate` TEXT NOT NULL)"
        )
        database.execSQL("UPDATE hints SET kind = 'pool' WHERE kind = 'stack'")
    }
}

class InterceptionOutcomeConverter {
    @TypeConverter
    fun toName(outcome: InterceptionOutcome): String = outcome.name

    @TypeConverter
    fun fromName(name: String): InterceptionOutcome = InterceptionOutcome.valueOf(name)
}
