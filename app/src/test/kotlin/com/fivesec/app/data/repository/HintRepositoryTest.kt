package com.fivesec.app.data.repository

import com.fivesec.app.data.db.HintDao
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.HintKind
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HintRepository 消费逻辑测试（specs/004-custom-hints）：
 * LIFO 顺序、栈空回落合并池随机、一次性消费防复活（按 id 过滤）、入口校验、持久化转发。
 * 快照收集走真实后台协程，用轮询 await 观测就绪；DAO 用 StateFlow 手控 fake（insert/delete 仅记录）。
 */
class HintRepositoryTest {

    /** 手控 fake：observeByKind 返回 StateFlow，测试手动改值模拟 Room 重发；写操作仅记录。 */
    private class FakeHintDao : HintDao {
        val stackState = MutableStateFlow<List<Hint>>(emptyList())
        val poolState = MutableStateFlow<List<Hint>>(emptyList())
        val inserted = CopyOnWriteArrayList<Hint>()
        val deletedIds = CopyOnWriteArrayList<Long>()

        override suspend fun insert(hint: Hint): Long {
            inserted += hint
            return inserted.size.toLong()
        }

        override fun observeByKind(kind: String): Flow<List<Hint>> =
            if (kind == HintKind.STACK) stackState else poolState

        override suspend fun deleteById(id: Long) {
            deletedIds += id
        }
    }

    private fun awaitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("await timeout")
            Thread.sleep(10)
        }
    }

    @Test
    fun `栈非空时LIFO消费-最新优先`() {
        val dao = FakeHintDao()
        dao.stackState.value = listOf(Hint(id = 1, text = "A", kind = HintKind.STACK), Hint(id = 2, text = "B", kind = HintKind.STACK))
        val repo = HintRepository(dao)

        // 快照就绪观测：builtin 单元素 X；stack 未就绪时返回 X，就绪后消费栈顶 B
        awaitUntil { repo.takeNextHint(listOf("X")) == "B" }
        // 第二次：次新 A；第三次：栈空回落 builtin（唯一元素，确定性）
        assertEquals("A", repo.takeNextHint(listOf("X")))
        assertEquals("X", repo.takeNextHint(listOf("X")))
        // 消费的两条均已异步请求删库
        awaitUntil { dao.deletedIds.containsAll(listOf(2L, 1L)) }
    }

    @Test
    fun `栈空时从内置加自定义池合并随机`() {
        val dao = FakeHintDao()
        dao.poolState.value = listOf(Hint(id = 10, text = "P", kind = HintKind.POOL))
        val repo = HintRepository(dao)

        // pool 不被消费，轮询安全；builtin 空 + 池就绪后恒返回 P（唯一元素确定性）
        awaitUntil { repo.takeNextHint(emptyList()) == "P" }

        // 并入随机：builtin [X] + pool [P]，100 次中 P 与 X 均出现（(1/2)^100 概率性缺席≈0）
        var sawPool = false
        var sawBuiltin = false
        repeat(100) {
            when (repo.takeNextHint(listOf("X"))) {
                "P" -> sawPool = true
                "X" -> sawBuiltin = true
            }
        }
        assertTrue("池条目应参与随机", sawPool)
        assertTrue("内置条目应参与随机", sawBuiltin)
    }

    @Test
    fun `删库未落地时observe重发不会复活已消费栈顶`() {
        val dao = FakeHintDao()
        dao.stackState.value = listOf(Hint(id = 1, text = "A", kind = HintKind.STACK), Hint(id = 2, text = "B", kind = HintKind.STACK))
        val repo = HintRepository(dao)

        awaitUntil { repo.takeNextHint(listOf("X")) == "B" } // 消费 B

        // 模拟异步删库尚未落地时 observe 重发（列表仍含 B）
        dao.stackState.value = listOf(
            Hint(id = 1, text = "A", kind = HintKind.STACK),
            Hint(id = 2, text = "B", kind = HintKind.STACK),
        )

        // 无论如何 B 不得复活：下一次必须消费 A
        assertEquals("A", repo.takeNextHint(listOf("X")))
        // 删库落库后的重发（列表已不含 B）：栈空回落
        dao.stackState.value = listOf(Hint(id = 1, text = "A", kind = HintKind.STACK))
        awaitUntil { repo.takeNextHint(listOf("X")) == "X" }
    }

    @Test
    fun `防复活按id过滤-同文本新行不受误伤`() {
        val dao = FakeHintDao()
        dao.stackState.value = listOf(Hint(id = 1, text = "A", kind = HintKind.STACK))
        val repo = HintRepository(dao)

        awaitUntil { repo.takeNextHint(listOf("X")) == "A" } // 消费 id=1，pending={1}

        // 重新入栈同文本（新 id=2）：observe 重发含旧行与新行
        dao.stackState.value = listOf(
            Hint(id = 1, text = "A", kind = HintKind.STACK),
            Hint(id = 2, text = "A", kind = HintKind.STACK),
        )

        // 新行不被 pending 过滤 → 仍可消费一次
        assertEquals("A", repo.takeNextHint(listOf("X")))
        assertEquals("X", repo.takeNextHint(listOf("X")))
    }

    @Test
    fun `pushStackHint校验-空白拒绝超长截断`() {
        val dao = FakeHintDao()
        val repo = HintRepository(dao)

        repo.pushStackHint("   ")
        repo.pushStackHint("")
        Thread.sleep(100) // 排空可能存在的（不该发生的）后台 launch
        assertEquals(0, dao.inserted.size)

        repo.pushStackHint("  保留首尾空白  ")
        repo.pushStackHint("一二三四五六七八九十一二三四五六七八九十一二三四十") // 40 字
        awaitUntil { dao.inserted.size == 2 }

        val saved = dao.inserted.map { it.text }
        assertEquals(listOf("保留首尾空白", "一二三四五六七八九十一二三四五六七八九十一二三四十".take(HintRepository.MAX_HINT_LENGTH)), saved)
        assertEquals(listOf(HintKind.STACK, HintKind.STACK), dao.inserted.map { it.kind })
        assertEquals(30, HintRepository.MAX_HINT_LENGTH)
    }

    @Test
    fun `addPoolHint与removePoolHint转发`() {
        val dao = FakeHintDao()
        val repo = HintRepository(dao)

        repo.addPoolHint("早点睡觉")
        awaitUntil { dao.inserted.size == 1 }
        assertEquals(HintKind.POOL, dao.inserted.single().kind)

        repo.removePoolHint(42)
        awaitUntil { dao.deletedIds.contains(42L) }
    }
}
