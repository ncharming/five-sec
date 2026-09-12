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
    fun `按kind过滤且id升序返回`() = runTest {
        dao.insert(Hint(text = "第一条", kind = HintKind.STACK))
        dao.insert(Hint(text = "池条目", kind = HintKind.POOL))
        dao.insert(Hint(text = "第二条", kind = HintKind.STACK))

        val stack = dao.observeByKind(HintKind.STACK).first()
        assertEquals(listOf("第一条", "第二条"), stack.map { it.text })
        // 栈序 = 入库序，栈顶为最后一个元素
        assertTrue(stack.last().id > stack.first().id)

        val pool = dao.observeByKind(HintKind.POOL).first()
        assertEquals(listOf("池条目"), pool.map { it.text })
    }

    @Test
    fun `deleteById删除指定行且不影响其他行`() = runTest {
        val id1 = dao.insert(Hint(text = "保留", kind = HintKind.STACK))
        val id2 = dao.insert(Hint(text = "删除我", kind = HintKind.STACK))

        dao.deleteById(id2)

        val stack = dao.observeByKind(HintKind.STACK).first()
        assertEquals(listOf("保留"), stack.map { it.text })
        assertEquals(id1, stack.single().id)
    }

    @Test
    fun `删除池条目不影响栈条目`() = runTest {
        dao.insert(Hint(text = "栈", kind = HintKind.STACK))
        val poolId = dao.insert(Hint(text = "池", kind = HintKind.POOL))

        dao.deleteById(poolId)

        assertTrue(dao.observeByKind(HintKind.POOL).first().isEmpty())
        assertEquals(listOf("栈"), dao.observeByKind(HintKind.STACK).first().map { it.text })
    }

    @Test
    fun `允许重复文本`() = runTest {
        dao.insert(Hint(text = "重复", kind = HintKind.POOL))
        dao.insert(Hint(text = "重复", kind = HintKind.POOL))

        assertEquals(2, dao.observeByKind(HintKind.POOL).first().size)
    }
}
