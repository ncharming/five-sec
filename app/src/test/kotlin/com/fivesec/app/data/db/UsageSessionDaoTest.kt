package com.fivesec.app.data.db

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.UsageSession
import com.fivesec.app.domain.model.UsageSessionEndReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [UsageSessionDao] 聚合边界（specs/012）：按包名区间求和、endedAt 半开区间归期。 */
@RunWith(RobolectricTestRunner::class)
class UsageSessionDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun session(
        pkg: String,
        startedAt: Long,
        endedAt: Long,
        reason: UsageSessionEndReason = UsageSessionEndReason.GUARD_ENDED,
        guardShownCount: Int = 1,
    ) = UsageSession(
        packageName = pkg,
        startedAt = startedAt,
        endedAt = endedAt,
        durationMillis = endedAt - startedAt,
        guardShownCount = guardShownCount,
        endReason = reason,
    )

    @Test
    fun `按包名聚合同期会话时长且endedAt半开区间`() = runTest {
        val pkgA = "com.ss.android.ugc.aweme"
        val pkgB = "tv.danmaku.bili"
        db.usageSessionDao().insert(session(pkgA, startedAt = 0, endedAt = 900))    // 区间前（endedAt=900）
        db.usageSessionDao().insert(session(pkgA, startedAt = 100, endedAt = 1000)) // 计入
        db.usageSessionDao().insert(session(pkgA, startedAt = 200, endedAt = 1100, guardShownCount = 0)) // 计入
        db.usageSessionDao().insert(session(pkgA, startedAt = 300, endedAt = 1200)) // 区间后（endedAt=1200=end，半开排除）
        db.usageSessionDao().insert(session(pkgB, startedAt = 0, endedAt = 1000))   // 另一应用，计入 pkgB

        val rows = db.usageSessionDao().observeDurationByPackageBetween(start = 1000, end = 1200).first()
            .associateBy { it.packageName }

        assertEquals(1800L, rows[pkgA]?.totalMillis) // (1000-100)+(1100-200)=900+900
        assertEquals(2, rows[pkgA]?.sessionCount)
        assertEquals(1000L, rows[pkgB]?.totalMillis)
        assertEquals(1, rows[pkgB]?.sessionCount)
    }

    @Test
    fun `区间内无会话返回空列表`() = runTest {
        db.usageSessionDao().insert(session("com.ss.android.ugc.aweme", startedAt = 0, endedAt = 100))
        val rows = db.usageSessionDao().observeDurationByPackageBetween(start = 200, end = 300).first()
        assertTrue(rows.isEmpty())
    }
}
