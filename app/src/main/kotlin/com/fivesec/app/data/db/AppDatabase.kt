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
import com.fivesec.app.domain.model.TodoCompletion

@Database(
    entities = [TargetApp::class, InterceptionEvent::class, Hint::class, Todo::class, TodoCompletion::class],
    version = 8, // v8：新增 todo_completions 完成事件表（specs/008-todo-stats，统计页任务历史数据源）
    exportSchema = false,
)
@TypeConverters(InterceptionOutcomeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun targetAppDao(): TargetAppDao
    abstract fun interceptionEventDao(): InterceptionEventDao
    abstract fun hintDao(): HintDao
    abstract fun todoDao(): TodoDao
    abstract fun todoCompletionDao(): TodoCompletionDao
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

// 从版本5迁移到版本6：todos 新增重复规则三列（specs/006-recurring-todos）。
// 纯 ADD COLUMN + DEFAULT 0：存量行自动归入"每天"规则，零数据迁移语句、零丢失；
// 列定义必须与 Room 为 Todo 实体生成的 schema 逐字一致（AppDatabaseMigrationTest 守住）。
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE todos ADD COLUMN repeatType INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE todos ADD COLUMN repeatDays INTEGER NOT NULL DEFAULT 0")
        database.execSQL("ALTER TABLE todos ADD COLUMN intervalDays INTEGER NOT NULL DEFAULT 0")
    }
}

// 从版本6迁移到版本7：todos 新增 createdAt（创建日，展示用）/ dueDate（一次性有效期日）两列
// （specs/007-oneoff-todos）。纯 ADD COLUMN + DEFAULT ''：存量行空串——创建时间显示「—」不伪造，
// 且存量全是重复类规则，空 dueDate 零语义影响；零数据迁移语句、零丢失；
// 列定义必须与 Room 为 Todo 实体生成的 schema 逐字一致（AppDatabaseMigrationTest 守住）。
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE todos ADD COLUMN createdAt TEXT NOT NULL DEFAULT ''")
        database.execSQL("ALTER TABLE todos ADD COLUMN dueDate TEXT NOT NULL DEFAULT ''")
    }
}

// 从版本7迁移到版本8：新增 todo_completions 完成事件表（specs/008-todo-stats，任务历史统计数据源）。
// 纯 CREATE TABLE + UNIQUE INDEX：既有四表零触碰（interception_events 红线）；无回填——部署前的
// 完成历史不存在，统计从升级日起积累（0 值是有效数据，不伪造）；列与索引定义必须与 Room 为
// TodoCompletion 生成的 schema 逐字一致（AppDatabaseMigrationTest 手建 v7 库守住）。
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `todo_completions` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`todoId` INTEGER NOT NULL, " +
                "`todoText` TEXT NOT NULL, " +
                "`completedDate` TEXT NOT NULL)"
        )
        database.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_todo_completions_todoId_completedDate` " +
                "ON `todo_completions` (`todoId`, `completedDate`)"
        )
    }
}

class InterceptionOutcomeConverter {
    @TypeConverter
    fun toName(outcome: InterceptionOutcome): String = outcome.name

    @TypeConverter
    fun fromName(name: String): InterceptionOutcome = InterceptionOutcome.valueOf(name)
}
