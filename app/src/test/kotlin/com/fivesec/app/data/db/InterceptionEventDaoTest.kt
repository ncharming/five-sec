package com.fivesec.app.data.db

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.util.DateUtil
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
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
    fun `按包名聚合周期内拦截与打开数`() = runTest {
        // 小红书：3 次（2 打开，1 取消）；抖音：1 次（0 打开）
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 10, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 20, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 30, exerciseCompleted = true, outcome = InterceptionOutcome.CANCELED))
        dao.insert(InterceptionEvent(packageName = "com.ss.android.ugc.aweme", timestamp = 40, exerciseCompleted = true, outcome = InterceptionOutcome.CANCELED))

        val rows = dao.observeCountsByPackageBetween(rangeStart = 0, rangeEnd = Long.MAX_VALUE).first()

        val xhs = rows.first { it.packageName == "com.xingin.xhs" }
        assertEquals(3, xhs.total)
        assertEquals(2, xhs.opened)
        assertEquals(1, xhs.canceled)

        val dou = rows.first { it.packageName == "com.ss.android.ugc.aweme" }
        assertEquals(1, dou.total)
        assertEquals(0, dou.opened)
        assertEquals(1, dou.canceled)
    }

    @Test
    fun `早于周期起点的事件不计入聚合`() = runTest {
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 5, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 15, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))

        val rows = dao.observeCountsByPackageBetween(rangeStart = 10, rangeEnd = Long.MAX_VALUE).first()

        val xhs = rows.first { it.packageName == "com.xingin.xhs" }
        assertEquals(1, xhs.total)
        assertEquals(1, xhs.opened)
        assertEquals(0, xhs.canceled)
    }

    @Test
    fun `中断事件计入拦截总数但不计入打开与取消`() = runTest {
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 10, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "com.xingin.xhs", timestamp = 20, exerciseCompleted = false, outcome = InterceptionOutcome.INTERRUPTED))

        val rows = dao.observeCountsByPackageBetween(rangeStart = 0, rangeEnd = Long.MAX_VALUE).first()

        val xhs = rows.single()
        assertEquals(2, xhs.total)
        assertEquals(1, xhs.opened)
        assertEquals(0, xhs.canceled)
    }

    @Test
    fun `四档周期起点各自圈定事件范围且历史完整`() = runTest {
        val zone = ZoneId.of("Asia/Shanghai")
        fun millis(date: String, time: String = "12:00"): Long =
            ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), zone).toInstant().toEpochMilli()

        val now = millis("2026-09-09") // 周三
        val pkg = "com.xingin.xhs"
        // 去年（远早于年起点）+ 年初 + 上月（周起点之外）+ 今天
        dao.insert(InterceptionEvent(packageName = pkg, timestamp = millis("2025-03-15"), exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = pkg, timestamp = millis("2026-01-02"), exerciseCompleted = true, outcome = InterceptionOutcome.CANCELED))
        dao.insert(InterceptionEvent(packageName = pkg, timestamp = millis("2026-08-20"), exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = pkg, timestamp = now - 3_600_000, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))

        suspend fun totalBetween(start: Long, end: Long): Int =
            dao.observeCountsByPackageBetween(start, end).first().single().total

        assertEquals(1, totalBetween(DateUtil.startOfDayMillis(now, zone), millis("2026-09-10", "00:00")))   // 日：仅今天
        assertEquals(1, totalBetween(DateUtil.startOfWeekMillis(now, zone), millis("2026-09-14", "00:00")))  // 周：本周一 09-07 起
        assertEquals(1, totalBetween(DateUtil.startOfMonthMillis(now, zone), millis("2026-10-01", "00:00"))) // 月：09-01 起
        assertEquals(3, totalBetween(DateUtil.startOfYearMillis(now, zone), millis("2027-01-01", "00:00")))  // 年：2026-01-01 起
    }

    @Test
    fun `历史事件早于任何周期时年档仍完整计入`() = runTest {
        dao.insert(InterceptionEvent(packageName = "tv.danmaku.bili", timestamp = 1_000, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "tv.danmaku.bili", timestamp = 2_000, exerciseCompleted = true, outcome = InterceptionOutcome.CANCELED))

        val rows = dao.observeCountsByPackageBetween(rangeStart = 1_500, rangeEnd = Long.MAX_VALUE).first()

        val bili = rows.single()
        assertEquals(1, bili.total) // 首条早于起点不计，次条计入：验证闭区间起点语义
        assertEquals(0, bili.opened)
        assertEquals(1, bili.canceled)
    }

    @Test
    fun `周期排他上界之后的事件不计入聚合`() = runTest {
        dao.insert(InterceptionEvent(packageName = "tv.danmaku.bili", timestamp = 1_000, exerciseCompleted = true, outcome = InterceptionOutcome.OPENED))
        dao.insert(InterceptionEvent(packageName = "tv.danmaku.bili", timestamp = 2_000, exerciseCompleted = true, outcome = InterceptionOutcome.CANCELED))

        val rows = dao.observeCountsByPackageBetween(rangeStart = 1_000, rangeEnd = 2_000).first()

        assertEquals(1, rows.single().total)
    }

    /** 空态契约锚点：统计页不再因"无记录"整页消失，前提是查询层无记录时
     *  照常执行并发射零值（而非不发射/返回 null），这里锁死该前提。 */
    @Test
    fun `空表时今日计数查询照常执行且返回零值`() = runTest {
        val startOfToday = DateUtil.startOfDayMillis(System.currentTimeMillis())

        assertEquals(0, dao.observeTodayCount(startOfToday).first())
        assertEquals(0, dao.observeTodayCountByOutcome(startOfToday, InterceptionOutcome.OPENED).first())
        assertEquals(0, dao.observeTodayCountByOutcome(startOfToday, InterceptionOutcome.CANCELED).first())
    }

    @Test
    fun `空表时按应用聚合与连击日期查询返回空集合而非不发射`() = runTest {
        assertEquals(emptyList<PackageRangeCount>(), dao.observeCountsByPackageBetween(0, Long.MAX_VALUE).first())
        assertEquals(emptyList<String>(), dao.observeActiveDays().first())
        assertEquals(null, dao.observeEarliestTimestamp().first())
    }
}
