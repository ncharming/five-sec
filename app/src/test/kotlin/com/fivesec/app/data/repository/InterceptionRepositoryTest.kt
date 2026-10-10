package com.fivesec.app.data.repository

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.data.db.AppDatabase
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.TimeProvider
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 今日抵制序号内存镜像口径（specs/011）：播种对齐 / 同日递增 / 跨日归零 / 镜像领先事件表。
 * 日期期望值一律用与生产代码同源的 DateUtil（默认时区）推导，测试不自带第二套时区口径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class InterceptionRepositoryTest {

    @get:Rule val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var repository: InterceptionRepository

    // 固定时钟：2026-10-10 12:00（日期串/日界毫秒按默认时区与生产代码同源推导）
    private val fixedNow = LocalDate.parse("2026-10-10").atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val today: String get() = DateUtil.todayString(fixedNow)
    private val tomorrow: String get() = DateUtil.todayString(fixedNow + 86_400_000)
    private val startOfToday: Long get() = DateUtil.startOfDayMillis(fixedNow)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = InterceptionRepository(db.interceptionEventDao(), TimeProvider { fixedNow })
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertCanceledAt(ts: Long) {
        db.interceptionEventDao().insert(
            InterceptionEvent(packageName = "com.xingin.xhs", timestamp = ts, exerciseCompleted = true, outcome = InterceptionOutcome.CANCELED),
        )
    }

    @Test
    fun `播种后从事件表今日取消数起算`() = runTest {
        insertCanceledAt(startOfToday + 3_600_000)
        insertCanceledAt(startOfToday + 7_200_000)
        repository.reseedResistedMirror()

        assertEquals(3, repository.nextResistedOrdinal(today)) // 事件表 2 次 + 本次
        assertEquals(4, repository.nextResistedOrdinal(today))
    }

    @Test
    fun `播种只认今日取消不含昨日`() = runTest {
        insertCanceledAt(startOfToday - 3_600_000) // 昨日 23:00
        insertCanceledAt(startOfToday + 60_000)    // 今日 00:01
        repository.reseedResistedMirror()

        assertEquals(2, repository.nextResistedOrdinal(today))
    }

    @Test
    fun `跨日归零重新从一起算`() = runTest {
        repository.reseedResistedMirror()
        assertEquals(1, repository.nextResistedOrdinal(today))
        assertEquals(2, repository.nextResistedOrdinal(today))
        assertEquals(1, repository.nextResistedOrdinal(tomorrow)) // 新的一天
        assertEquals(2, repository.nextResistedOrdinal(tomorrow))
    }

    @Test
    fun `镜像领先事件表时再次播种取最大值不回退`() = runTest {
        insertCanceledAt(startOfToday + 60_000)
        repository.reseedResistedMirror()
        assertEquals(2, repository.nextResistedOrdinal(today)) // 镜像 2，事件表仍 1（落库在 0.8s 展示后）

        repository.reseedResistedMirror() // 进程重启播种：max(镜像, 事件表) 不回退
        assertEquals(3, repository.nextResistedOrdinal(today))
    }

    @Test
    fun `未播种直接取号从一起算`() {
        assertEquals(1, repository.nextResistedOrdinal(today))
        assertEquals(2, repository.nextResistedOrdinal(today))
    }
}
