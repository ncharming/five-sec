package com.fivesec.app.data.repository

import com.fivesec.app.data.db.TodoDao
import com.fivesec.app.domain.model.Todo
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TodoRepository 测试（specs/005-daily-todos）：
 * 快照过滤（只含启用项）、完成态按日期惰性求值、入口校验（空白/200 字）、上限 20、
 * 定向 UPDATE 转发（rename/setEnabled/setCompleted）。
 * 快照收集走真实后台协程，用轮询 await 观测就绪；DAO 用 StateFlow 手控 fake（写操作仅记录）。
 */
class TodoRepositoryTest {

    /** 手控 fake：observeAll 返回 StateFlow，测试手动改值模拟 Room 重发；写操作记录待断言。 */
    private class FakeTodoDao : TodoDao {
        val state = MutableStateFlow<List<Todo>>(emptyList())
        val inserted = CopyOnWriteArrayList<Todo>()
        val renamed = CopyOnWriteArrayList<Pair<Long, String>>()
        val enabledChanges = CopyOnWriteArrayList<Pair<Long, Boolean>>()
        val completionChanges = CopyOnWriteArrayList<Triple<Long, String, Boolean>>()
        val deletedIds = CopyOnWriteArrayList<Long>()

        override fun observeAll(): Flow<List<Todo>> = state

        override suspend fun insert(todo: Todo): Long {
            inserted += todo
            state.value = state.value + todo.copy(id = inserted.size.toLong())
            return inserted.size.toLong()
        }

        override suspend fun updateText(id: Long, text: String) {
            renamed += id to text
            state.value = state.value.map { if (it.id == id) it.copy(text = text) else it }
        }

        override suspend fun setEnabled(id: Long, enabled: Boolean) {
            enabledChanges += id to enabled
            state.value = state.value.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
        }

        override suspend fun setCompletedDate(id: Long, date: String) {
            completionChanges += Triple(id, date, date.isNotEmpty())
            state.value = state.value.map { if (it.id == id) it.copy(lastCompletedDate = date) else it }
        }

        override suspend fun deleteById(id: Long) {
            deletedIds += id
            state.value = state.value.filterNot { it.id == id }
        }

        override suspend fun count(): Int = state.value.size
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("await timeout")
            Thread.sleep(10)
        }
    }

    private val today = "2026-09-23"

    @Test
    fun `todayTodos只含启用项且完成态按日期惰性求值`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(
            Todo(id = 1, text = "今日已完成", isEnabled = true, lastCompletedDate = today),
            Todo(id = 2, text = "未完成", isEnabled = true, lastCompletedDate = ""),
            Todo(id = 3, text = "昨日完成", isEnabled = true, lastCompletedDate = "2026-09-22"),
            Todo(id = 4, text = "已停用", isEnabled = false, lastCompletedDate = today),
        )
        val repo = TodoRepository(dao)

        awaitUntil { repo.todayTodos(today).size == 3 } // 快照就绪（含停用前的 3 条启用项）

        val rows = repo.todayTodos(today)
        assertEquals(listOf("今日已完成", "未完成", "昨日完成"), rows.map { it.text })
        assertEquals(listOf(true, false, false), rows.map { it.isDone }) // 昨日完成跨日自动失效
    }

    @Test
    fun `add空白拒绝且不落库`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao)

        assertTrue(repo.add("   ").isFailure)
        assertTrue(repo.add("").isFailure)
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `add超长截断200字`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao)
        val overlong = "背".repeat(210) // 超过 200 字上限的输入

        val result = repo.add("  $overlong  ")

        assertTrue(result.isSuccess)
        assertEquals(overlong.take(TodoRepository.MAX_TEXT_LENGTH), dao.inserted.single().text)
        assertEquals(TodoRepository.MAX_TEXT_LENGTH, dao.inserted.single().text.length)
    }

    @Test
    fun `add不超上限时trim后原文保留不截断`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao)
        val forty = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四十"

        val result = repo.add("  $forty  ")

        assertTrue(result.isSuccess)
        assertEquals(forty, dao.inserted.single().text) // 40 字在 200 上限内，trim 后原样入库
    }

    @Test
    fun `add达上限20后拒绝`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = (1..TodoRepository.MAX_TODOS).map { Todo(id = it.toLong(), text = "条目$it") }
        val repo = TodoRepository(dao)

        val result = repo.add("第 21 条")

        assertTrue(result.isFailure)
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `setCompleted勾选写当日日期取消清空`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao)

        repo.setCompleted(id = 1, today = today, completed = true)
        repo.setCompleted(id = 1, today = today, completed = false)

        assertEquals(
            listOf(Triple(1L, today, true), Triple(1L, "", false)),
            dao.completionChanges.toList(),
        )
    }

    @Test
    fun `rename与remove与setEnabled按定向更新转发`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧文案"))
        val repo = TodoRepository(dao)

        assertTrue(repo.rename(7, "  新文案  ").isSuccess)
        assertTrue(repo.rename(7, "   ").isFailure) // 同一校验口径
        repo.setEnabled(7, false)
        repo.remove(7)
        awaitUntil { dao.deletedIds.contains(7L) }

        assertEquals(listOf(7L to "新文案"), dao.renamed.toList())
        assertEquals(listOf(7L to false), dao.enabledChanges.toList())
        assertFalse(dao.state.value.any { it.id == 7L })
    }
}
