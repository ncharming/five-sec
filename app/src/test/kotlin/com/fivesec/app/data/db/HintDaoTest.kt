package com.fivesec.app.data.db

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.HintKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * HintDao 测试（specs/005-daily-todos 去栈化）：
 * kind 只剩 pool 口径（stack 已随拦截页输入入口退役，存量行由 MIGRATION_4_5 改挂 pool）。
 */
@RunWith(RobolectricTestRunner::class)
class HintDaoTest {

    @get:Rule val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var dao: HintDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.hintDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `池条目按id升序返回`() = runTest {
        dao.insert(Hint(text = "第一条", kind = HintKind.POOL))
        dao.insert(Hint(text = "第二条", kind = HintKind.POOL))

        val pool = dao.observeByKind(HintKind.POOL).first()
        assertEquals(listOf("第一条", "第二条"), pool.map { it.text })
        assertTrue(pool.last().id > pool.first().id) // 添加顺序 = 轮转顺序
    }

    @Test
    fun `deleteById删除指定行且不影响其他行`() = runTest {
        val id1 = dao.insert(Hint(text = "保留", kind = HintKind.POOL))
        val id2 = dao.insert(Hint(text = "删除我", kind = HintKind.POOL))

        dao.deleteById(id2)

        val pool = dao.observeByKind(HintKind.POOL).first()
        assertEquals(listOf("保留"), pool.map { it.text })
        assertEquals(id1, pool.single().id)
    }

    @Test
    fun `允许重复文本`() = runTest {
        dao.insert(Hint(text = "重复", kind = HintKind.POOL))
        dao.insert(Hint(text = "重复", kind = HintKind.POOL))

        assertEquals(2, dao.observeByKind(HintKind.POOL).first().size)
    }
}
