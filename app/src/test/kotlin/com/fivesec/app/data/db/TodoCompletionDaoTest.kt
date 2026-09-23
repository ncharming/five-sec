package com.fivesec.app.data.db

import android.app.Application
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.TodoCompletion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * todo_completions DAO 聚合测试（specs/008-todo-stats，in-memory Room + Robolectric）：
 * 当天唯一（upsert 幂等）/取消删当日行/半开日期串区间聚合/GROUP BY 排序稳定性/最早日期。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TodoCompletionDaoTest {

    @get:Rule val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase
    private lateinit var dao: TodoCompletionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = db.todoCompletionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `同一天同一条目upsert后仍只一行`() = runTest {
        dao.upsert(TodoCompletion(todoId = 1, todoText = "背单词", completedDate = "2026-09-23"))
        dao.upsert(TodoCompletion(todoId = 1, todoText = "背单词", completedDate = "2026-09-23")) // 重勾兜底

        val rows = dao.observeCountByTodoBetween(startDate = "2026-09-23", endDate = "2026-09-24").first()

        assertEquals(1, rows.single().completions)
    }

    @Test
    fun `取消勾选删除当日行_他日行不受影响`() = runTest {
        dao.upsert(TodoCompletion(todoId = 1, todoText = "背单词", completedDate = "2026-09-22"))
        dao.upsert(TodoCompletion(todoId = 1, todoText = "背单词", completedDate = "2026-09-23"))

        dao.deleteByTodoAndDate(todoId = 1, date = "2026-09-23")

        val rows = dao.observeCountByTodoBetween(startDate = "2000-01-01", endDate = "2999-12-31").first()
        assertEquals(1, rows.single().completions)
        assertEquals("2026-09-22", dao.observeEarliestDate().first())
    }

    @Test
    fun `半开区间聚合_端点日不计入_同日重勾只算一次`() = runTest {
        // 条目 A：周一、周三各一次；条目 B：周二一次、周四勾-取消-再勾（同日仍只一行）
        dao.upsert(TodoCompletion(todoId = 1, todoText = "A", completedDate = "2026-09-21")) // 周一
        dao.upsert(TodoCompletion(todoId = 2, todoText = "B", completedDate = "2026-09-22")) // 周二
        dao.upsert(TodoCompletion(todoId = 1, todoText = "A", completedDate = "2026-09-23")) // 周三
        dao.upsert(TodoCompletion(todoId = 2, todoText = "B", completedDate = "2026-09-24")) // 周四
        dao.upsert(TodoCompletion(todoId = 2, todoText = "B", completedDate = "2026-09-24")) // 同日重勾 → REPLACE 覆盖

        // 本周 [09-21, 09-28)：A=2、B=2（同日不重复计数）；B 次数并列按文本排序在后
        val week = dao.observeCountByTodoBetween("2026-09-21", "2026-09-28").first()
        assertEquals(listOf("A" to 2, "B" to 2), week.map { it.todoText to it.completions })

        // 从周三起 [09-23, 09-28)：端点日 09-21/09-22 不计入 → 各剩 1
        val fromWednesday = dao.observeCountByTodoBetween("2026-09-23", "2026-09-28").first()
        assertEquals(listOf("A" to 1, "B" to 1), fromWednesday.map { it.todoText to it.completions })
    }

    @Test
    fun `按条目聚合次数降序_同次数按文本升序`() = runTest {
        dao.upsert(TodoCompletion(todoId = 1, todoText = "乙", completedDate = "2026-09-21"))
        dao.upsert(TodoCompletion(todoId = 2, todoText = "甲", completedDate = "2026-09-22"))
        dao.upsert(TodoCompletion(todoId = 2, todoText = "甲", completedDate = "2026-09-23"))
        dao.upsert(TodoCompletion(todoId = 3, todoText = "丙", completedDate = "2026-09-23"))

        val rows = dao.observeCountByTodoBetween("2026-09-21", "2026-09-28").first()

        assertEquals(listOf("甲" to 2, "丙" to 1, "乙" to 1), rows.map { it.todoText to it.completions })
    }

    @Test
    fun `无记录时最早日期为null_区间聚合为空`() = runTest {
        assertNull(dao.observeEarliestDate().first())
        assertEquals(emptyList<TodoRangeCount>(), dao.observeCountByTodoBetween("2000-01-01", "2999-12-31").first())
    }
}
