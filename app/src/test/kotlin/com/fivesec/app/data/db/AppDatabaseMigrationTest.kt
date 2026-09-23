package com.fivesec.app.data.db

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.HintKind
import com.fivesec.app.domain.model.Todo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v2 → v3 迁移：事件流水零丢失、冗余汇总表安全删除（历史留存红线）。 */
@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {

    @Test
    fun `迁移后事件全部保留且汇总表已删除`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "migration-test.db"
        context.deleteDatabase(dbName)

        // 以 v2 结构手工建库（target_apps / interception_events / app_statistics）
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE target_apps (" +
                                "packageName TEXT NOT NULL PRIMARY KEY, " +
                                "appName TEXT NOT NULL, " +
                                "isEnabled INTEGER NOT NULL, " +
                                "isDefault INTEGER NOT NULL, " +
                                "addedAt INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE interception_events (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "packageName TEXT NOT NULL, " +
                                "timestamp INTEGER NOT NULL, " +
                                "exerciseCompleted INTEGER NOT NULL, " +
                                "outcome TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE app_statistics (" +
                                "packageName TEXT NOT NULL PRIMARY KEY, " +
                                "totalInterceptions INTEGER NOT NULL, " +
                                "cancellations INTEGER NOT NULL, " +
                                "cancellationRate REAL NOT NULL, " +
                                "completedExercises INTEGER NOT NULL, " +
                                "lastUpdated INTEGER NOT NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO target_apps VALUES ('com.xingin.xhs', '小红书', 1, 1, 100)")
            db.execSQL("INSERT INTO interception_events (packageName, timestamp, exerciseCompleted, outcome) VALUES ('com.xingin.xhs', 111, 1, 'OPENED')")
            db.execSQL("INSERT INTO interception_events (packageName, timestamp, exerciseCompleted, outcome) VALUES ('com.ss.android.ugc.aweme', 222, 0, 'CANCELED')")
            db.execSQL("INSERT INTO app_statistics VALUES ('com.xingin.xhs', 9, 9, 1.0, 9, 999)")
        }
        helper.close()

        // 以 Room v5 打开：v3 库依次触发 MIGRATION_2_3/3_4/4_5（缺任一会抛迁移缺失异常）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        val rows = db.interceptionEventDao().observeCountsByPackageBetween(rangeStart = 0, rangeEnd = Long.MAX_VALUE).first()
        assertEquals(2, rows.size)
        val xhs = rows.first { it.packageName == "com.xingin.xhs" }
        assertEquals(1, xhs.total)
        assertEquals(1, xhs.opened)
        assertEquals(0, xhs.canceled)
        val douyin = rows.first { it.packageName == "com.ss.android.ugc.aweme" }
        assertEquals(1, douyin.total)
        assertEquals(0, douyin.opened)
        assertEquals(1, douyin.canceled)

        db.openHelper.readableDatabase
            .query("SELECT name FROM sqlite_master WHERE type='table' AND name='app_statistics'")
            .use { cursor -> assertFalse("app_statistics 应已被迁移删除", cursor.moveToFirst()) }

        db.close()
        context.deleteDatabase(dbName)
    }

    /** v3 → v4 迁移：既有两表数据零丢失，hints 表新建可用（specs/004-custom-hints）。 */
    @Test
    fun `v4迁移后既有数据保留且hints表可用`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "migration-test-v4.db"
        context.deleteDatabase(dbName)

        // 以 v3 结构手工建库（target_apps / interception_events；v3 已无 app_statistics）
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE target_apps (" +
                                "packageName TEXT NOT NULL PRIMARY KEY, " +
                                "appName TEXT NOT NULL, " +
                                "isEnabled INTEGER NOT NULL, " +
                                "isDefault INTEGER NOT NULL, " +
                                "addedAt INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE interception_events (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "packageName TEXT NOT NULL, " +
                                "timestamp INTEGER NOT NULL, " +
                                "exerciseCompleted INTEGER NOT NULL, " +
                                "outcome TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO target_apps VALUES ('com.xingin.xhs', '小红书', 1, 1, 100)")
            db.execSQL("INSERT INTO interception_events (packageName, timestamp, exerciseCompleted, outcome) VALUES ('com.xingin.xhs', 111, 1, 'OPENED')")
        }
        helper.close()

        // 以 Room v5 打开：触发 MIGRATION_3_4 与 MIGRATION_4_5（完整迁移链）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        // 既有数据保留
        assertEquals(1, db.targetAppDao().observeAll().first().size)
        assertEquals(
            1,
            db.interceptionEventDao()
                .observeCountsByPackageBetween(rangeStart = 0, rangeEnd = Long.MAX_VALUE)
                .first().single().total,
        )

        // hints 表可读写（005 去栈化：只剩 pool 口径）
        db.hintDao().insert(Hint(text = "池条目", kind = HintKind.POOL))
        db.hintDao().insert(Hint(text = "迁移后新增", kind = HintKind.POOL))
        assertEquals(listOf("池条目", "迁移后新增"), db.hintDao().observeByKind(HintKind.POOL).first().map { it.text })

        db.close()
        context.deleteDatabase(dbName)
    }

    /** v4 → v5 迁移：todos 表新建可用；hints 存量 stack 行全部改挂 pool，用户文字零丢失（specs/005）。 */
    @Test
    fun `v5迁移后todos表可用且stack提示改挂pool`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "migration-test-v5.db"
        context.deleteDatabase(dbName)

        // 以 v4 结构手工建库（target_apps / interception_events / hints；hints 允许 kind=stack）
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE target_apps (" +
                                "packageName TEXT NOT NULL PRIMARY KEY, " +
                                "appName TEXT NOT NULL, " +
                                "isEnabled INTEGER NOT NULL, " +
                                "isDefault INTEGER NOT NULL, " +
                                "addedAt INTEGER NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE interception_events (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "packageName TEXT NOT NULL, " +
                                "timestamp INTEGER NOT NULL, " +
                                "exerciseCompleted INTEGER NOT NULL, " +
                                "outcome TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS hints (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "text TEXT NOT NULL, " +
                                "kind TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO target_apps VALUES ('com.xingin.xhs', '小红书', 1, 1, 100)")
            db.execSQL("INSERT INTO interception_events (packageName, timestamp, exerciseCompleted, outcome) VALUES ('com.xingin.xhs', 111, 1, 'OPENED')")
            db.execSQL("INSERT INTO hints (text, kind) VALUES ('别刷了，去写作业', 'stack')")
            db.execSQL("INSERT INTO hints (text, kind) VALUES ('池条目', 'pool')")
        }
        helper.close()

        // 以 Room v5 打开：触发 MIGRATION_4_5
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()

        // 既有数据保留
        assertEquals(1, db.targetAppDao().observeAll().first().size)
        assertEquals(
            1,
            db.interceptionEventDao()
                .observeCountsByPackageBetween(rangeStart = 0, rangeEnd = Long.MAX_VALUE)
                .first().single().total,
        )

        // stack 全部改挂 pool：用户留的话并入池，零丢失；库中不再有 stack 行
        val poolTexts = db.hintDao().observeByKind(HintKind.POOL).first().map { it.text }
        assertEquals(listOf("别刷了，去写作业", "池条目"), poolTexts)
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM hints WHERE kind = 'stack'")
            .use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }

        // todos 表可读写（列定义与 Room 生成的 schema 逐字一致，否则打开即抛校验异常）
        db.todoDao().insert(Todo(text = "背 20 个单词", isEnabled = true, lastCompletedDate = ""))
        assertEquals(listOf("背 20 个单词"), db.todoDao().observeAll().first().map { it.text })

        db.close()
        context.deleteDatabase(dbName)
    }
}
