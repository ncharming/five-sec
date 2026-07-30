package com.fivesec.app.settings

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.data.db.AppDatabase
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.domain.model.TargetApp
import com.fivesec.app.settings.viewmodels.AppListViewModel
import com.fivesec.app.util.PackageUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppListViewModelTest {

    @get:Rule val instantExecutorRule = InstantTaskExecutorRule()

    private val dispatcher = StandardTestDispatcher()

    private lateinit var db: AppDatabase
    private lateinit var repository: TargetAppRepository
    private lateinit var viewModel: AppListViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = TargetAppRepository(db.targetAppDao())
        viewModel = AppListViewModel(context, repository)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `添加应用后出现在清单中`() = runTest(dispatcher) {
        viewModel.add("com.sina.weibo", now = 1_000)
        advanceUntilIdle()
        val apps = viewModel.targetApps.first()
        assertTrue(apps.any { it.packageName == "com.sina.weibo" })
    }

    @Test
    fun `移除应用后从清单消失`() = runTest(dispatcher) {
        repository.insertAll(listOf(TargetApp("com.sina.weibo", appName = "微博", isEnabled = true, isDefault = false, addedAt = 0)))
        viewModel.remove("com.sina.weibo")
        advanceUntilIdle()
        val apps = viewModel.targetApps.first { it.isEmpty() }
        assertEquals(0, apps.size)
    }

    @Test
    fun `切换单应用开关`() = runTest(dispatcher) {
        repository.insertAll(listOf(TargetApp("tv.danmaku.bili", appName = "哔哩哔哩", isEnabled = true, isDefault = true, addedAt = 0)))
        viewModel.setEnabled("tv.danmaku.bili", enabled = false)
        advanceUntilIdle()
        val bili = viewModel.targetApps.first().first { it.packageName == "tv.danmaku.bili" }
        assertEquals(false, bili.isEnabled)
    }

    @Test
    fun `空查询返回全部候选并标记已添加项`() {
        val candidates = listOf(
            PackageUtil.InstalledApp("com.xingin.xhs", "小红书"),
            PackageUtil.InstalledApp("com.sina.weibo", "微博"),
        )
        val result = PackageUtil.filterInstalledApps(candidates, setOf("com.xingin.xhs"), query = "")
        assertEquals(2, result.size)
        assertTrue(result.first { it.app.packageName == "com.xingin.xhs" }.isAdded)
        assertFalse(result.first { it.app.packageName == "com.sina.weibo" }.isAdded)
    }

    @Test
    fun `按名称不分大小写匹配`() {
        val candidates = listOf(
            PackageUtil.InstalledApp("com.xingin.xhs", "小红书"),
            PackageUtil.InstalledApp("com.sina.weibo", "微博"),
        )
        val result = PackageUtil.filterInstalledApps(candidates, emptySet(), query = "小红")
        assertEquals(1, result.size)
        assertEquals("com.xingin.xhs", result.first().app.packageName)
    }

    @Test
    fun `按包名匹配`() {
        val candidates = listOf(
            PackageUtil.InstalledApp("com.sina.weibo", "微博"),
            PackageUtil.InstalledApp("com.xingin.xhs", "小红书"),
        )
        val result = PackageUtil.filterInstalledApps(candidates, emptySet(), query = "com.sina")
        assertEquals(1, result.size)
        assertEquals("微博", result.first().app.label)
    }

    @Test
    fun `无匹配返回空`() {
        val candidates = listOf(PackageUtil.InstalledApp("com.xingin.xhs", "小红书"))
        val result = PackageUtil.filterInstalledApps(candidates, emptySet(), query = "zzzqqq")
        assertTrue(result.isEmpty())
    }
}
