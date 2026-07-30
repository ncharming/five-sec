package com.fivesec.app.data.db

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InterceptionEventDaoTest {

    @get:Rule val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var dao: InterceptionEventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.interceptionEventDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `按包名聚合今日拦截与打开数`() = runTest {
        // 小红书：3 次（2 打开，1 取消）；抖音：1 次（0 打开）
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 10, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 20, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 30, exerciseCompleted = true, outcome = InterceptionOutcome.CANCELED))
        dao.insert(InterceptionEvent(packageName = "com.ss.android.ugc.aweme", timestamp = 40, exerciseCompleted = true, outcome = InterceptionOutcome.CANCELED))

        val rows = dao.observeTodayCountsByPackage(startOfDay = 0).first()

        val xhs = rows.first { it.packageName == "com.xingin.xhs" }
        assertEquals(3, xhs.total)
        assertEquals(2, xhs.opened)

        val dou = rows.first { it.packageName == "com.ss.android.ugc.aweme" }
        assertEquals(1, dou.total)
        assertEquals(0, dou.opened)
    }

    @Test
    fun `早于 startOfDay 的事件不计入今日`() = runTest {
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 5, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 15, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))

        val rows = dao.observeTodayCountsByPackage(startOfDay = 10).first()

        val xhs = rows.first { it.packageName == "com.xingin.xhs" }
        assertEquals(1, xhs.total)
        assertEquals(1, xhs.opened)
    }
}
