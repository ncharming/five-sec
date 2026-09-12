package com.fivesec.app.data.db

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.HintKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

        // 以 Room v4 打开：v2 库依次触发 MIGRATION_2_3 与 MIGRATION_3_4（缺 3_4 会抛迁移缺失异常）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

        // 以 Room v4 打开：触发 MIGRATION_3_4
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
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

        // hints 表可读写
        db.hintDao().insert(Hint(text = "迁移后新增", kind = HintKind.STACK))
        db.hintDao().insert(Hint(text = "池条目", kind = HintKind.POOL))
        assertEquals(listOf("迁移后新增"), db.hintDao().observeByKind(HintKind.STACK).first().map { it.text })
        assertTrue(db.hintDao().observeByKind(HintKind.POOL).first().isNotEmpty())

        db.close()
        context.deleteDatabase(dbName)
    }
}
