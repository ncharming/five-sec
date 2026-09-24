package com.fivesec.app.data.db

import android.app.Application
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoCompletion
import com.fivesec.app.util.TodoRecurrence
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

        // 以 Room v9 打开：v2 库依次触发 MIGRATION_2_3/3_4/4_5/5_6/6_7/7_8/8_9（缺任一会抛迁移缺失异常）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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

        // 以 Room v9 打开：触发 MIGRATION_3_4/4_5/5_6/6_7/7_8/8_9（完整迁移链）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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

        // hints 表迁移链路上可用（005 去栈化后只剩 pool 口径）；009 起实体退役无 DAO，
        // 以 raw SQL 验证物理表仍可读写（数据墓碑：表保留、应用零声明）
        db.openHelper.writableDatabase.execSQL("INSERT INTO hints (text, kind) VALUES ('池条目', 'pool')")
        db.openHelper.writableDatabase.execSQL("INSERT INTO hints (text, kind) VALUES ('迁移后新增', 'pool')")
        db.openHelper.readableDatabase.query("SELECT text FROM hints WHERE kind = 'pool' ORDER BY id ASC").use { cursor ->
            val texts = mutableListOf<String>()
            while (cursor.moveToNext()) texts += cursor.getString(0)
            assertEquals(listOf("池条目", "迁移后新增"), texts)
        }

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

        // 以 Room v9 打开：触发 MIGRATION_4_5/5_6/6_7/7_8/8_9
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
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
        // （009 起 hints 无 DAO，raw SQL 直查物理表）
        db.openHelper.readableDatabase.query("SELECT text FROM hints WHERE kind = 'pool' ORDER BY id ASC").use { cursor ->
            val poolTexts = mutableListOf<String>()
            while (cursor.moveToNext()) poolTexts += cursor.getString(0)
            assertEquals(listOf("别刷了，去写作业", "池条目"), poolTexts)
        }
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

    /** v5 → v6 迁移：老待办零丢失且规则列默认"每天"，行为与升级前逐位一致（specs/006-recurring-todos）。 */
    @Test
    fun `v6迁移后老待办保留且规则列默认每天`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "migration-test-v6.db"
        context.deleteDatabase(dbName)

        // 以 v5 结构手工建库（四表；todos 为 005 的四列 schema，无规则列）
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
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
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS todos (" +
                                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "text TEXT NOT NULL, " +
                                "isEnabled INTEGER NOT NULL, " +
                                "lastCompletedDate TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            // 三种老数据形态：启用已勾选 / 启用未勾选 / 停用
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate) VALUES ('已勾选', 1, '2026-09-23')")
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate) VALUES ('未勾选', 1, '')")
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate) VALUES ('已停用', 0, '2026-09-23')")
        }
        helper.close()

        // 以 Room v9 打开：触发 MIGRATION_5_6/6_7/7_8/8_9（注册完整链，缺迁移即抛异常的既有守卫模式）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        // 行零丢失、字段值不变、规则三列默认 0（= 每天）
        val rows = db.todoDao().observeAll().first()
        assertEquals(3, rows.size)
        val byText = rows.associateBy { it.text }
        assertEquals("2026-09-23", byText.getValue("已勾选").lastCompletedDate)
        assertEquals(true, byText.getValue("已勾选").isEnabled)
        assertEquals("", byText.getValue("未勾选").lastCompletedDate)
        assertEquals(false, byText.getValue("已停用").isEnabled)
        rows.forEach {
            assertEquals(TodoRecurrence.REPEAT_DAILY, it.repeatType)
            assertEquals(0, it.repeatDays)
            assertEquals(0, it.intervalDays)
        }

        // v6 打开后新列可正常读写（带规则写入并读回）
        db.todoDao().insert(
            Todo(text = "升级后新增", isEnabled = true, lastCompletedDate = "", intervalDays = 3, repeatType = TodoRecurrence.REPEAT_INTERVAL),
        )
        val inserted = db.todoDao().observeAll().first().single { it.text == "升级后新增" }
        assertEquals(TodoRecurrence.REPEAT_INTERVAL, inserted.repeatType)
        assertEquals(3, inserted.intervalDays)

        db.close()
        context.deleteDatabase(dbName)
    }

    /** v6 → v7 迁移：老待办零丢失，createdAt/dueDate 回填空串（specs/007-oneoff-todos，展示「—」不伪造）。 */
    @Test
    fun `v7迁移后老待办保留且两列回填空串`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "migration-test-v7.db"
        context.deleteDatabase(dbName)

        // 以 v6 结构手工建库（四表；todos 为 006 的七列 schema，无 createdAt/dueDate）
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
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
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `todos` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`text` TEXT NOT NULL, " +
                                "`isEnabled` INTEGER NOT NULL, " +
                                "`lastCompletedDate` TEXT NOT NULL, " +
                                "`repeatType` INTEGER NOT NULL, " +
                                "`repeatDays` INTEGER NOT NULL, " +
                                "`intervalDays` INTEGER NOT NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            // 三种 v6 形态：每天默认 / 周几规则 / 间隔规则（含启用与完成态差异）
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate, repeatType, repeatDays, intervalDays) VALUES ('每天条目', 1, '2026-09-23', 0, 0, 0)")
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate, repeatType, repeatDays, intervalDays) VALUES ('周一三五', 1, '', 1, 21, 0)")
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate, repeatType, repeatDays, intervalDays) VALUES ('每3天', 0, '2026-09-20', 2, 0, 3)")
        }
        helper.close()

        // 以 Room v9 打开：触发 MIGRATION_6_7/7_8/8_9（列定义与实体 schema 逐字一致，否则打开即抛校验异常）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        // 行零丢失、既有六列逐位不变、新两列回填空串（老条目创建时间显示「—」的口径来源）
        val rows = db.todoDao().observeAll().first()
        assertEquals(3, rows.size)
        val byText = rows.associateBy { it.text }
        assertEquals(TodoRecurrence.REPEAT_DAILY, byText.getValue("每天条目").repeatType)
        assertEquals("2026-09-23", byText.getValue("每天条目").lastCompletedDate)
        assertEquals(TodoRecurrence.REPEAT_WEEKLY, byText.getValue("周一三五").repeatType)
        assertEquals(21, byText.getValue("周一三五").repeatDays)
        assertEquals(TodoRecurrence.REPEAT_INTERVAL, byText.getValue("每3天").repeatType)
        assertEquals(3, byText.getValue("每3天").intervalDays)
        assertEquals(false, byText.getValue("每3天").isEnabled)
        rows.forEach {
            assertEquals("", it.createdAt)
            assertEquals("", it.dueDate)
        }

        // v7 打开后新列可正常读写（一次性规则写入并读回）
        db.todoDao().insert(
            Todo(
                text = "升级后新增一次性",
                repeatType = TodoRecurrence.REPEAT_ONCE,
                createdAt = "2026-09-23",
                dueDate = "2026-09-23",
            ),
        )
        val inserted = db.todoDao().observeAll().first().single { it.text == "升级后新增一次性" }
        assertEquals(TodoRecurrence.REPEAT_ONCE, inserted.repeatType)
        assertEquals("2026-09-23", inserted.createdAt)
        assertEquals("2026-09-23", inserted.dueDate)

        db.close()
        context.deleteDatabase(dbName)
    }

    /** v7 → v8 迁移：老待办零丢失，todo_completions 完成事件表新建可用（specs/008-todo-stats）。 */
    @Test
    fun `v8迁移后老待办保留且完成事件表可用`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "migration-test-v8.db"
        context.deleteDatabase(dbName)

        // 以 v7 结构手工建库（四表；todos 为 007 的九列 schema，无 todo_completions）
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
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
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `todos` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`text` TEXT NOT NULL, " +
                                "`isEnabled` INTEGER NOT NULL, " +
                                "`lastCompletedDate` TEXT NOT NULL, " +
                                "`repeatType` INTEGER NOT NULL, " +
                                "`repeatDays` INTEGER NOT NULL, " +
                                "`intervalDays` INTEGER NOT NULL, " +
                                "`createdAt` TEXT NOT NULL, " +
                                "`dueDate` TEXT NOT NULL)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            // 三种 v7 形态：每天 / 一次性当天 / 一次性过期（含 createdAt/dueDate 真实值）
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate, repeatType, repeatDays, intervalDays, createdAt, dueDate) VALUES ('每天条目', 1, '2026-09-23', 0, 0, 0, '2026-09-01', '')")
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate, repeatType, repeatDays, intervalDays, createdAt, dueDate) VALUES ('今天寄快递', 1, '', 3, 0, 0, '2026-09-23', '2026-09-23')")
            db.execSQL("INSERT INTO todos (text, isEnabled, lastCompletedDate, repeatType, repeatDays, intervalDays, createdAt, dueDate) VALUES ('过期条目', 1, '', 3, 0, 0, '2026-09-20', '2026-09-20')")
        }
        helper.close()

        // 以 Room v9 打开：触发 MIGRATION_7_8/8_9（建表+建唯一索引，列定义逐字一致否则打开即抛校验异常）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        // 老待办三行零丢失、九列逐位不变
        val rows = db.todoDao().observeAll().first()
        assertEquals(3, rows.size)
        val byText = rows.associateBy { it.text }
        assertEquals("2026-09-01", byText.getValue("每天条目").createdAt)
        assertEquals("", byText.getValue("每天条目").dueDate)
        assertEquals(TodoRecurrence.REPEAT_ONCE, byText.getValue("今天寄快递").repeatType)
        assertEquals("2026-09-23", byText.getValue("今天寄快递").dueDate)
        assertEquals("2026-09-20", byText.getValue("过期条目").dueDate)

        // todo_completions 可读写：同一天同条 upsert 仍只一行（唯一索引随迁移生效）
        val completionDao = db.todoCompletionDao()
        completionDao.upsert(TodoCompletion(todoId = 1, todoText = "每天条目", completedDate = "2026-09-23"))
        completionDao.upsert(TodoCompletion(todoId = 1, todoText = "每天条目", completedDate = "2026-09-23"))
        completionDao.upsert(TodoCompletion(todoId = 2, todoText = "今天寄快递", completedDate = "2026-09-23"))
        completionDao.upsert(TodoCompletion(todoId = 1, todoText = "每天条目", completedDate = "2026-09-22"))
        assertEquals(
            listOf("每天条目" to 2, "今天寄快递" to 1),
            completionDao.observeCountByTodoBetween("2026-09-22", "2026-09-24").first()
                .map { it.todoText to it.completions },
        )
        assertEquals("2026-09-22", completionDao.observeEarliestDate().first())

        db.close()
        context.deleteDatabase(dbName)
    }

    /** v8 → v9 迁移：提示语退役的空迁移——hints 物理表与存量行原样保留，其余表零触碰（specs/009-retire-hints）。 */
    @Test
    fun `v9迁移后hints表存量保留且其余表零丢失`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val dbName = "migration-test-v9.db"
        context.deleteDatabase(dbName)

        // 以 v8 结构手工建库（五表齐：target_apps / interception_events / hints / todos / todo_completions）
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
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
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `todos` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`text` TEXT NOT NULL, " +
                                "`isEnabled` INTEGER NOT NULL, " +
                                "`lastCompletedDate` TEXT NOT NULL, " +
                                "`repeatType` INTEGER NOT NULL, " +
                                "`repeatDays` INTEGER NOT NULL, " +
                                "`intervalDays` INTEGER NOT NULL, " +
                                "`createdAt` TEXT NOT NULL, " +
                                "`dueDate` TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE TABLE IF NOT EXISTS `todo_completions` (" +
                                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                "`todoId` INTEGER NOT NULL, " +
                                "`todoText` TEXT NOT NULL, " +
                                "`completedDate` TEXT NOT NULL)"
                        )
                        db.execSQL(
                            "CREATE UNIQUE INDEX IF NOT EXISTS `index_todo_completions_todoId_completedDate` " +
                                "ON `todo_completions` (`todoId`, `completedDate`)"
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.use { db ->
            db.execSQL("INSERT INTO hints (text, kind) VALUES ('自定义A', 'pool')")
            db.execSQL("INSERT INTO hints (text, kind) VALUES ('自定义B', 'pool')")
            db.execSQL("INSERT INTO hints (text, kind) VALUES ('自定义C', 'pool')")
            db.execSQL(
                "INSERT INTO todos (text, isEnabled, lastCompletedDate, repeatType, repeatDays, intervalDays, createdAt, dueDate) " +
                    "VALUES ('每天条目', 1, '', 0, 0, 0, '2026-09-23', '')"
            )
            db.execSQL("INSERT INTO todo_completions (todoId, todoText, completedDate) VALUES (1, '每天条目', '2026-09-23')")
            db.execSQL("INSERT INTO interception_events (packageName, timestamp, exerciseCompleted, outcome) VALUES ('com.ss.android.ugc.aweme', 111, 1, 'CANCELED')")
        }
        helper.close()

        // 以 Room v9 打开：触发 MIGRATION_8_9（空迁移——仅重写 identity hash，物理表一行不动）
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
            .allowMainThreadQueries()
            .build()

        // hints 物理表与 3 行存量原样保留（raw query——009 起无 DAO，可追溯不可用）
        db.openHelper.readableDatabase.query("SELECT text FROM hints ORDER BY id ASC").use { cursor ->
            val texts = mutableListOf<String>()
            while (cursor.moveToNext()) texts += cursor.getString(0)
            assertEquals(listOf("自定义A", "自定义B", "自定义C"), texts)
        }

        // 其余表零丢失：待办、完成事件、拦截事件照常可读
        assertEquals(listOf("每天条目"), db.todoDao().observeAll().first().map { it.text })
        assertEquals("2026-09-23", db.todoCompletionDao().observeEarliestDate().first())
        assertEquals(
            1,
            db.interceptionEventDao()
                .observeCountsByPackageBetween(rangeStart = 0, rangeEnd = Long.MAX_VALUE)
                .first().single().total,
        )

        db.close()
        context.deleteDatabase(dbName)
    }
}
