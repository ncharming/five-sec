package com.fivesec.app.data.repository

import com.fivesec.app.data.datastore.BuiltinHintsSetting
import com.fivesec.app.data.db.HintDao
import com.fivesec.app.domain.model.Hint
import com.fivesec.app.domain.model.HintKind
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HintRepository 循环展示测试（specs/005-daily-todos；004 的栈式用例随机制退役删除）：
 * 序列 = 内置（参数顺序，受内置开关过滤）+ 池（id 升序），按持久化游标取模循环；
 * 跨实例续接；序列缩短取模不越界；开关关闭仅剩池参与轮换、池空返回空串。
 * 快照收集走真实后台协程，用轮询 await 观测就绪；DAO/游标存储/开关均用手控 fake（纯 JVM）。
 */
class HintRepositoryTest {

    /** 手控 fake：observeByKind 返回 StateFlow，测试手动改值模拟 Room 重发；写操作仅记录。 */
    private class FakeHintDao : HintDao {
        val poolState = MutableStateFlow<List<Hint>>(emptyList())
        val inserted = CopyOnWriteArrayList<Hint>()
        val deletedIds = CopyOnWriteArrayList<Long>()

        override suspend fun insert(hint: Hint): Long {
            inserted += hint
            return inserted.size.toLong()
        }

        override fun observeByKind(kind: String): Flow<List<Hint>> = poolState

        override suspend fun deleteById(id: Long) {
            deletedIds += id
        }
    }

    /** 手控游标存储：observe/write 直连同一 StateFlow，模拟 DataStore 持久化。 */
    private class FakeCursorStore : HintCursorStore {
        val state = MutableStateFlow(0)
        val writes = CopyOnWriteArrayList<Int>()

        override fun observeCursor(): Flow<Int> = state

        override suspend fun writeCursor(value: Int) {
            writes += value
            state.value = value
        }
    }

    /** 手控 fake 开关源：StateFlow 改值即模拟持久化配置变化（仓库经后台协程收集）。 */
    private class FakeBuiltinHintsSetting(initial: Boolean = true) : BuiltinHintsSetting {
        val state = MutableStateFlow(initial)
        override val builtinHintsEnabled: Flow<Boolean> = state
        override suspend fun setBuiltinHintsEnabled(enabled: Boolean) {
            state.value = enabled
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
    fun `循环展示按内置加池顺序轮转且末尾回环`() = runTest {
        val dao = FakeHintDao()
        dao.poolState.value = listOf(Hint(id = 10, text = "P", kind = HintKind.POOL))
        val repo = HintRepository(dao, FakeCursorStore(), FakeBuiltinHintsSetting())

        // 预热：builtin 传空等待池快照就绪；空序列不推进游标，就绪后唯一元素 P 恰好消耗一次推进（游标 → 1）
        awaitUntil { repo.takeNextHint(emptyList()) == "P" }

        // 序列 [A, B, P]，从下标 1 起：B → P → A（回环）
        assertEquals("B", repo.takeNextHint(listOf("A", "B")))
        assertEquals("P", repo.takeNextHint(listOf("A", "B")))
        assertEquals("A", repo.takeNextHint(listOf("A", "B")))
        assertEquals("B", repo.takeNextHint(listOf("A", "B")))
        // 池条目常驻：取值不删行
        assertEquals(0, dao.deletedIds.size)
    }

    @Test
    fun `游标跨实例续接-新Repository从持久化位置继续`() = runTest {
        val dao = FakeHintDao()
        val store = FakeCursorStore()
        val repo = HintRepository(dao, store, FakeBuiltinHintsSetting())

        // 内置 3 条取 4 次：游标停在「下一个下标 + 1」口径的 1（取 6 次会停在 3，与归零同样映射到 "A"，测不出真续接）
        val builtin = listOf("A", "B", "C")
        repeat(4) { repo.takeNextHint(builtin) }
        awaitUntil { store.state.value == 1 } // 收敛队列把游标落库（StateFlow 只保最新值，合并 1,2,3,1）

        // 新实例挂同一游标存储（模拟进程重启）：续接下标 1 → "B"；若游标归零则会给 "A"
        val restarted = HintRepository(dao, store, FakeBuiltinHintsSetting())
        awaitUntil { restarted.cursorForTest() == 1 } // 等 observe 初值落地，防初值 0 抢跑
        assertEquals("B", restarted.takeNextHint(builtin))
    }

    @Test
    fun `序列缩短后游标取模不越界`() {
        val dao = FakeHintDao()
        val repo = HintRepository(dao, FakeCursorStore(), FakeBuiltinHintsSetting())

        val three = listOf("A", "B", "C")
        assertEquals("A", repo.takeNextHint(three))
        assertEquals("B", repo.takeNextHint(three)) // 游标 = 2

        // 序列缩到 1 条：下标 2 % 1 = 0，不越界
        assertEquals("A", repo.takeNextHint(listOf("A")))
        // 回到 3 条：下标 1 → "B"
        assertEquals("B", repo.takeNextHint(three))
    }

    @Test
    fun `空序列返回空串防御不抛异常`() {
        val dao = FakeHintDao()
        val repo = HintRepository(dao, FakeCursorStore(), FakeBuiltinHintsSetting())

        // builtin 契约恒非空（资源数组）；此处故意传空验证防御路径
        assertEquals("", repo.takeNextHint(emptyList()))
    }

    @Test
    fun `内置开关关闭后内置条目退出循环-仅剩自定义池轮换`() = runTest {
        val dao = FakeHintDao()
        dao.poolState.value = listOf(Hint(id = 10, text = "P", kind = HintKind.POOL))
        val setting = FakeBuiltinHintsSetting(initial = true)
        val repo = HintRepository(dao, FakeCursorStore(), setting)

        // 开启期：内置 A 参与序列（[A, P] 轮换，交替出现）
        awaitUntil { repo.takeNextHint(listOf("A")) == "P" }
        assertEquals("A", repo.takeNextHint(listOf("A")))

        // 关闭：开关经后台协程收集落地。单次取到 P 不能证明已切换——[A,P] 交替序列同样会给出 P
        // （取完 P 下一个恰是 A，后续断言就会偶发失败，CI 慢机上已复现）。交替序列不可能连续
        // 出现两个 P，故「连续两次 P」是切换生效的确定性证明。
        setting.state.value = false
        awaitUntil {
            repo.takeNextHint(listOf("A")) == "P" && repo.takeNextHint(listOf("A")) == "P"
        }
        assertEquals("P", repo.takeNextHint(listOf("A")))
        assertEquals("P", repo.takeNextHint(listOf("A")))

        // 重新开启：内置条目恢复参与轮换（[A, P]，游标续接）
        setting.state.value = true
        awaitUntil {
            repo.takeNextHint(listOf("A"))
            repo.takeNextHint(listOf("A")) == "A"
        }
    }

    @Test
    fun `内置关闭且池空时返回空串-不兜底内置`() = runTest {
        val dao = FakeHintDao()
        val setting = FakeBuiltinHintsSetting(initial = true)
        val repo = HintRepository(dao, FakeCursorStore(), setting)

        // 开启时序列含内置（确定性：builtin [X] 池空）
        awaitUntil { repo.takeNextHint(listOf("X")) == "X" }

        // 关闭后无任何候选 → 空串（覆盖层提示语区无内容，不再兜底内置第一条）
        setting.state.value = false
        awaitUntil { repo.takeNextHint(listOf("X")).isEmpty() }
    }

    @Test
    fun `addPoolHint与removePoolHint转发且校验口径不变`() = runTest {
        val dao = FakeHintDao()
        val repo = HintRepository(dao, FakeCursorStore(), FakeBuiltinHintsSetting())

        repo.addPoolHint("   ") // 空白拒绝
        repo.addPoolHint("")
        Thread.sleep(100) // 排空可能存在的（不该发生的）后台 launch
        assertEquals(0, dao.inserted.size)

        repo.addPoolHint("  早点睡觉  ")
        awaitUntil { dao.inserted.size == 1 }
        assertEquals("早点睡觉", dao.inserted.single().text)
        assertEquals(HintKind.POOL, dao.inserted.single().kind)

        repo.removePoolHint(42)
        awaitUntil { dao.deletedIds.contains(42L) }
    }
}
