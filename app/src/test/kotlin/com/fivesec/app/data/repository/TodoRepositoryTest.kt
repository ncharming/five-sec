package com.fivesec.app.data.repository

import com.fivesec.app.data.db.TodoDao
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TodoRepository 测试（specs/005-daily-todos；006 增重复规则）：
 * 快照过滤（只含启用且今天轮到）、完成态按日期惰性求值、入口校验（空白/200 字/周几空集/间隔收敛）、
 * 上限 20、定向 UPDATE 转发（rename/setEnabled/setCompleted/setRecurrence）。
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
        val recurrenceCalls = CopyOnWriteArrayList<RecurrenceCall>()

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

        override suspend fun updateRecurrence(id: Long, repeatType: Int, repeatDays: Int, intervalDays: Int) {
            recurrenceCalls += RecurrenceCall(id, repeatType, repeatDays, intervalDays)
            state.value = state.value.map {
                if (it.id == id) it.copy(repeatType = repeatType, repeatDays = repeatDays, intervalDays = intervalDays) else it
            }
        }

        override suspend fun count(): Int = state.value.size
    }

    /** setRecurrence 定向 UPDATE 的转发记录（四元组，断言精确列值）。 */
    private data class RecurrenceCall(val id: Long, val type: Int, val days: Int, val interval: Int)

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

    // ── 重复规则（specs/006） ──

    @Test
    fun `todayTodos周几条目只在其日期轮到`() = runTest {
        val dao = FakeTodoDao()
        val mask = TodoRecurrence.bitOf(DayOfWeek.MONDAY) or
            TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY) or
            TodoRecurrence.bitOf(DayOfWeek.FRIDAY)
        dao.state.value = listOf(
            Todo(id = 1, text = "周一三五", repeatType = TodoRecurrence.REPEAT_WEEKLY, repeatDays = mask),
            Todo(id = 2, text = "每天", repeatType = TodoRecurrence.REPEAT_DAILY),
        )
        val repo = TodoRepository(dao)

        awaitUntil { repo.todayTodos("2026-09-23").isNotEmpty() } // 快照就绪（每天条恒可见）

        assertEquals(listOf("周一三五", "每天"), repo.todayTodos("2026-09-23").map { it.text })
        assertEquals(listOf("每天"), repo.todayTodos("2026-09-24").map { it.text }) // 周四未命中
    }

    @Test
    fun `todayTodos间隔条目完成灰显期隐藏_第N天复活`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(
            Todo(
                id = 1,
                text = "每3天",
                repeatType = TodoRecurrence.REPEAT_INTERVAL,
                intervalDays = 3,
                lastCompletedDate = "2026-09-23",
            ),
        )
        val repo = TodoRepository(dao)

        awaitUntil { repo.todayTodos("2026-09-26").isNotEmpty() } // 快照就绪

        assertTrue(repo.todayTodos("2026-09-23").isEmpty()) // 完成当天
        assertTrue(repo.todayTodos("2026-09-25").isEmpty()) // 灰显期
        assertEquals("每3天", repo.todayTodos("2026-09-26").single().text) // 第 3 天复活
    }

    @Test
    fun `setRecurrence周几空集拒绝且不落库`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧文案"))
        val repo = TodoRepository(dao)

        val result = repo.setRecurrence(7, TodoRule(TodoRecurrence.REPEAT_WEEKLY, 0, 0))

        assertTrue(result.isFailure)
        assertEquals(0, dao.recurrenceCalls.size)
    }

    @Test
    fun `setRecurrence间隔越界收敛并定向转发三列不触碰锚点`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧文案", lastCompletedDate = "2026-09-23"))
        val repo = TodoRepository(dao)

        assertTrue(repo.setRecurrence(7, TodoRule(TodoRecurrence.REPEAT_INTERVAL, 0, 999)).isSuccess)

        assertEquals(
            listOf(RecurrenceCall(7L, TodoRecurrence.REPEAT_INTERVAL, 0, 365)),
            dao.recurrenceCalls.toList(),
        )
        assertEquals("2026-09-23", dao.state.value.single().lastCompletedDate) // 锚点未被触碰
    }

    @Test
    fun `setRecurrence周几规则落位掩码`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧文案"))
        val repo = TodoRepository(dao)
        val mask = TodoRecurrence.bitOf(DayOfWeek.MONDAY) or TodoRecurrence.bitOf(DayOfWeek.FRIDAY)

        assertTrue(repo.setRecurrence(7, TodoRule(TodoRecurrence.REPEAT_WEEKLY, mask, 0)).isSuccess)

        assertEquals(
            listOf(RecurrenceCall(7L, TodoRecurrence.REPEAT_WEEKLY, mask, 0)),
            dao.recurrenceCalls.toList(),
        )
    }

    @Test
    fun `add带周几规则落库`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao)

        val result = repo.add("锻炼", TodoRule.weekly(setOf(DayOfWeek.WEDNESDAY)))

        assertTrue(result.isSuccess)
        val inserted = dao.inserted.single()
        assertEquals(TodoRecurrence.REPEAT_WEEKLY, inserted.repeatType)
        assertEquals(TodoRecurrence.bitOf(DayOfWeek.WEDNESDAY), inserted.repeatDays)
        assertEquals(0, inserted.intervalDays)
    }

    @Test
    fun `add缺省规则落库为每天`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao)

        assertTrue(repo.add("普通每日").isSuccess)

        val inserted = dao.inserted.single()
        assertEquals(TodoRecurrence.REPEAT_DAILY, inserted.repeatType)
        assertEquals(0, inserted.repeatDays)
        assertEquals(0, inserted.intervalDays)
    }
}
