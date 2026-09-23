package com.fivesec.app.data.repository

import com.fivesec.app.data.db.TodoCompletionDao
import com.fivesec.app.data.db.TodoDao
import com.fivesec.app.data.db.TodoRangeCount
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoCompletion
import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.util.TimeProvider
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TodoRepository 测试（specs/005；006 重复规则；007 一次性/过期；008 完成事件双写）：
 * 快照过滤（只含启用且今天轮到；一次性当天计入、过期排除）、完成态按日期惰性求值、
 * 入口校验（空白/200 字/周几空集/间隔收敛）、上限 20、定向 UPDATE 转发、dueDate/createdAt 写入口径、
 * revive、惰性清理（已完成一次性跨日物理删除）、完成事件（勾选 upsert 含文本快照/取消删当日/重勾不重复）。
 * 快照收集与惰性清理走真实后台协程，用轮询 await 观测就绪；DAO 用 StateFlow 手控 fake
 * （写操作模拟 Room 重发，DELETE 同步作用于 state 以复现自稳定回路）。
 * 时钟：固定 2026-09-23T12:00:00Z（正午锚点，±12 时区内日期不变，CI UTC 可跑）。
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
        val dueDateCalls = CopyOnWriteArrayList<Pair<Long, String>>()
        val purgeCalls = CopyOnWriteArrayList<String>()

        override fun observeAll(): Flow<List<Todo>> = state

        override suspend fun findById(id: Long): Todo? = state.value.firstOrNull { it.id == id }

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

        override suspend fun updateRecurrence(
            id: Long,
            repeatType: Int,
            repeatDays: Int,
            intervalDays: Int,
            dueDate: String,
        ) {
            recurrenceCalls += RecurrenceCall(id, repeatType, repeatDays, intervalDays, dueDate)
            state.value = state.value.map {
                if (it.id == id) {
                    it.copy(repeatType = repeatType, repeatDays = repeatDays, intervalDays = intervalDays, dueDate = dueDate)
                } else {
                    it
                }
            }
        }

        override suspend fun setDueDate(id: Long, date: String) {
            dueDateCalls += id to date
            state.value = state.value.map { if (it.id == id) it.copy(dueDate = date) else it }
        }

        /** 模拟 Room 的 DELETE 语义：同步作用于 state（触发重发 → 收集器再跑一次无匹配行，自稳定）。 */
        override suspend fun purgeCompletedOneOffs(today: String) {
            purgeCalls += today
            state.value = state.value.filterNot {
                it.repeatType == TodoRecurrence.REPEAT_ONCE &&
                    it.lastCompletedDate.isNotEmpty() &&
                    it.lastCompletedDate != today
            }
        }

        override suspend fun count(): Int = state.value.size
    }

    /** setRecurrence 定向 UPDATE 的转发记录（五元组，断言精确列值含 dueDate）。 */
    private data class RecurrenceCall(
        val id: Long,
        val type: Int,
        val days: Int,
        val interval: Int,
        val dueDate: String,
    )

    /** 完成事件 fake：upsert 复现唯一索引语义（同 (todoId, 日期) 先删后插）；观察流按当前行快照发射。 */
    private class FakeTodoCompletionDao : TodoCompletionDao {
        val rows = CopyOnWriteArrayList<TodoCompletion>()

        override suspend fun upsert(completion: TodoCompletion) {
            rows.removeAll { it.todoId == completion.todoId && it.completedDate == completion.completedDate }
            rows += completion
        }

        override suspend fun deleteByTodoAndDate(todoId: Long, date: String) {
            rows.removeAll { it.todoId == todoId && it.completedDate == date }
        }

        override fun observeCountByTodoBetween(startDate: String, endDate: String): Flow<List<TodoRangeCount>> = flow {
            emit(
                rows.filter { it.completedDate >= startDate && it.completedDate < endDate }
                    .groupBy { it.todoId }
                    .map { (todoId, group) -> TodoRangeCount(todoId, group.first().todoText, group.size) }
                    .sortedWith(compareByDescending<TodoRangeCount> { it.completions }.thenBy { it.todoText }),
            )
        }

        override fun observeEarliestDate(): Flow<String?> = flow { emit(rows.minOfOrNull { it.completedDate }) }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("await timeout")
            Thread.sleep(10)
        }
    }

    private val today = "2026-09-23"

    // 正午 UTC 锚点：本地时区在 ±12 内换算仍是 2026-09-23（CI UTC 与开发机 CST 日期一致）
    private val nowMillis = Instant.parse("2026-09-23T12:00:00Z").toEpochMilli()
    private val timeProvider = TimeProvider { nowMillis }

    @Test
    fun `todayTodos只含启用项且完成态按日期惰性求值`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(
            Todo(id = 1, text = "今日已完成", isEnabled = true, lastCompletedDate = today),
            Todo(id = 2, text = "未完成", isEnabled = true, lastCompletedDate = ""),
            Todo(id = 3, text = "昨日完成", isEnabled = true, lastCompletedDate = "2026-09-22"),
            Todo(id = 4, text = "已停用", isEnabled = false, lastCompletedDate = today),
        )
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        awaitUntil { repo.todayTodos(today).size == 3 } // 快照就绪（含停用前的 3 条启用项）

        val rows = repo.todayTodos(today)
        assertEquals(listOf("今日已完成", "未完成", "昨日完成"), rows.map { it.text })
        assertEquals(listOf(true, false, false), rows.map { it.isDone }) // 昨日完成跨日自动失效
    }

    @Test
    fun `add空白拒绝且不落库`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        assertTrue(repo.add("   ").isFailure)
        assertTrue(repo.add("").isFailure)
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `add超长截断200字`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)
        val overlong = "背".repeat(210) // 超过 200 字上限的输入

        val result = repo.add("  $overlong  ")

        assertTrue(result.isSuccess)
        assertEquals(overlong.take(TodoRepository.MAX_TEXT_LENGTH), dao.inserted.single().text)
        assertEquals(TodoRepository.MAX_TEXT_LENGTH, dao.inserted.single().text.length)
    }

    @Test
    fun `add不超上限时trim后原文保留不截断`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)
        val forty = "一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四十"

        val result = repo.add("  $forty  ")

        assertTrue(result.isSuccess)
        assertEquals(forty, dao.inserted.single().text) // 40 字在 200 上限内，trim 后原样入库
    }

    @Test
    fun `add达上限20后拒绝`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = (1..TodoRepository.MAX_TODOS).map { Todo(id = it.toLong(), text = "条目$it") }
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        val result = repo.add("第 21 条")

        assertTrue(result.isFailure)
        assertEquals(0, dao.inserted.size)
    }

    @Test
    fun `setCompleted勾选写当日日期取消清空`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

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
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

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
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

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
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        awaitUntil { repo.todayTodos("2026-09-26").isNotEmpty() } // 快照就绪

        assertTrue(repo.todayTodos("2026-09-23").isEmpty()) // 完成当天
        assertTrue(repo.todayTodos("2026-09-25").isEmpty()) // 灰显期
        assertEquals("每3天", repo.todayTodos("2026-09-26").single().text) // 第 3 天复活
    }

    @Test
    fun `setRecurrence周几空集拒绝且不落库`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧文案"))
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        val result = repo.setRecurrence(7, TodoRule(TodoRecurrence.REPEAT_WEEKLY, 0, 0))

        assertTrue(result.isFailure)
        assertEquals(0, dao.recurrenceCalls.size)
    }

    @Test
    fun `setRecurrence间隔越界收敛并定向转发不触碰锚点`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧文案", lastCompletedDate = "2026-09-23"))
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        assertTrue(repo.setRecurrence(7, TodoRule(TodoRecurrence.REPEAT_INTERVAL, 0, 999)).isSuccess)

        assertEquals(
            listOf(RecurrenceCall(7L, TodoRecurrence.REPEAT_INTERVAL, 0, 365, "")),
            dao.recurrenceCalls.toList(),
        )
        assertEquals("2026-09-23", dao.state.value.single().lastCompletedDate) // 锚点未被触碰
        assertEquals("", dao.state.value.single().dueDate) // 重复类 dueDate 清空
    }

    @Test
    fun `setRecurrence周几规则落位掩码`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧文案"))
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)
        val mask = TodoRecurrence.bitOf(DayOfWeek.MONDAY) or TodoRecurrence.bitOf(DayOfWeek.FRIDAY)

        assertTrue(repo.setRecurrence(7, TodoRule(TodoRecurrence.REPEAT_WEEKLY, mask, 0)).isSuccess)

        assertEquals(
            listOf(RecurrenceCall(7L, TodoRecurrence.REPEAT_WEEKLY, mask, 0, "")),
            dao.recurrenceCalls.toList(),
        )
    }

    @Test
    fun `add带周几规则落库`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

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
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        assertTrue(repo.add("普通每日").isSuccess)

        val inserted = dao.inserted.single()
        assertEquals(TodoRecurrence.REPEAT_DAILY, inserted.repeatType)
        assertEquals(0, inserted.repeatDays)
        assertEquals(0, inserted.intervalDays)
    }

    // ── 一次性规则与过期（specs/007） ──

    @Test
    fun `add仅今天_创建日与有效期日都是当天`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        assertTrue(repo.add("今天寄快递", TodoRule.ONCE).isSuccess)

        val inserted = dao.inserted.single()
        assertEquals(TodoRecurrence.REPEAT_ONCE, inserted.repeatType)
        assertEquals(today, inserted.createdAt)
        assertEquals(today, inserted.dueDate)
    }

    @Test
    fun `add重复类_dueDate空串`() = runTest {
        val dao = FakeTodoDao()
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        assertTrue(repo.add("每天读书").isSuccess)

        val inserted = dao.inserted.single()
        assertEquals(today, inserted.createdAt) // 创建日与规则无关恒写
        assertEquals("", inserted.dueDate)
    }

    @Test
    fun `setRecurrence转仅今天_dueDate写当天_转回重复类清空`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧文案", createdAt = "2026-01-01"))
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        assertTrue(repo.setRecurrence(7, TodoRule.ONCE).isSuccess)
        assertEquals(
            listOf(RecurrenceCall(7L, TodoRecurrence.REPEAT_ONCE, 0, 0, today)),
            dao.recurrenceCalls.toList(),
        )

        assertTrue(repo.setRecurrence(7, TodoRule.DAILY).isSuccess)
        assertEquals(
            RecurrenceCall(7L, TodoRecurrence.REPEAT_DAILY, 0, 0, ""),
            dao.recurrenceCalls.last(),
        )
        assertEquals("2026-01-01", dao.state.value.single().createdAt) // 创建时间永不被改写
    }

    @Test
    fun `revive仅重写有效期日为当天`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(
            Todo(
                id = 9,
                text = "过期条目",
                repeatType = TodoRecurrence.REPEAT_ONCE,
                createdAt = "2026-09-20",
                dueDate = "2026-09-20",
            ),
        )
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        repo.revive(9)
        awaitUntil { dao.state.value.single().dueDate == today }

        assertEquals(listOf(9L to today), dao.dueDateCalls.toList())
        assertEquals("2026-09-20", dao.state.value.single().createdAt) // 创建时间不动
        assertEquals("", dao.state.value.single().lastCompletedDate) // 复活不清不写完成状态
    }

    @Test
    fun `todayTodos一次性当天计入_过期与非当天排除`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(
            Todo(id = 1, text = "今天寄快递", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = today),
            Todo(id = 2, text = "昨天没做完", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-22"),
            Todo(id = 3, text = "明天才轮到", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-24"),
            Todo(id = 4, text = "今天已完成", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = today, lastCompletedDate = today),
        )
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        awaitUntil { repo.todayTodos(today).isNotEmpty() }

        val rows = repo.todayTodos(today)
        assertEquals(listOf("今天寄快递", "今天已完成"), rows.map { it.text }) // 只有当天的两条进快照
        assertEquals(listOf(false, true), rows.map { it.isDone })
    }

    @Test
    fun `惰性清理_已完成一次性跨日物理删除_当天完成与未完成与重复类不动`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(
            Todo(id = 1, text = "昨天完成的一次性", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-22", lastCompletedDate = "2026-09-22"),
            Todo(id = 2, text = "今天完成的一次性", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = today, lastCompletedDate = today),
            Todo(id = 3, text = "昨天没完成的一次性", repeatType = TodoRecurrence.REPEAT_ONCE, dueDate = "2026-09-22"),
            Todo(id = 4, text = "昨天完成的每天", repeatType = TodoRecurrence.REPEAT_DAILY, lastCompletedDate = "2026-09-22"),
        )
        val repo = TodoRepository(dao, FakeTodoCompletionDao(), timeProvider)

        // 收集器首次发射后顺手 purge：僵尸行（id=1）消失，其余三条保留
        awaitUntil { dao.purgeCalls.isNotEmpty() }
        awaitUntil { dao.state.value.none { it.id == 1L } }

        val texts = dao.state.value.map { it.text }
        assertEquals(listOf("今天完成的一次性", "昨天没完成的一次性", "昨天完成的每天"), texts)
    }

    // ── 完成事件双写（specs/008） ──

    @Test
    fun `setCompleted勾选写完成事件_快照取勾选当时文本`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "背单词"))
        val completions = FakeTodoCompletionDao()
        val repo = TodoRepository(dao, completions, timeProvider)

        repo.setCompleted(7, today, completed = true)

        assertEquals(listOf(TodoCompletion(todoId = 7, todoText = "背单词", completedDate = today)), completions.rows)
    }

    @Test
    fun `setCompleted取消删当日事件_重勾不重复`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "背单词"))
        val completions = FakeTodoCompletionDao()
        val repo = TodoRepository(dao, completions, timeProvider)

        repo.setCompleted(7, today, completed = true)
        repo.setCompleted(7, today, completed = false)
        assertTrue(completions.rows.isEmpty()) // 取消即撤回当日计数

        repo.setCompleted(7, today, completed = true)
        assertEquals(1, completions.rows.size) // 同一天同条最多一行
        assertEquals(today, completions.rows.single().completedDate)
    }

    @Test
    fun `setCompleted改名后勾选_事件行按勾选当时的名字`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = listOf(Todo(id = 7, text = "旧名"))
        val completions = FakeTodoCompletionDao()
        val repo = TodoRepository(dao, completions, timeProvider)

        repo.setCompleted(7, "2026-09-22", completed = true) // 昨天以旧名勾选
        repo.rename(7, "新名")
        repo.setCompleted(7, today, completed = true) // 今天以新名勾选

        // 两天两行，各自是勾选当时的文本快照——历史不因改名失联
        assertEquals(
            listOf("旧名" to "2026-09-22", "新名" to today),
            completions.rows.sortedBy { it.completedDate }.map { it.todoText to it.completedDate },
        )
    }

    @Test
    fun `setCompleted条目已被删除_跳过事件写入不抛异常`() = runTest {
        val dao = FakeTodoDao()
        dao.state.value = emptyList() // id=7 不存在（并发删除后的残留勾选调用）
        val completions = FakeTodoCompletionDao()
        val repo = TodoRepository(dao, completions, timeProvider)

        repo.setCompleted(7, today, completed = true)

        assertEquals(1, dao.completionChanges.size) // todos 行照常转发（Room 侧无匹配行为 no-op）
        assertTrue(completions.rows.isEmpty()) // 无快照可写，静默跳过
    }
}
