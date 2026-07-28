package com.fivesec.app.interception

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试 InterceptionController 的纯逻辑核心 [CooldownGate]：去抖、抑制窗口、命中条件。 */
class InterceptionControllerTest {

    private val gate = CooldownGate(debounceMs = 800, suppressionMs = 5_000)

    @Test
    fun `命中目标且全局开启时拦截，并写入去抖冷却`() {
        // 第一次：命中
        assertTrue(gate.evaluate("com.ss.android.ugc.aweme", now = 0, globalEnabled = true, isTarget = true))
        // 去抖窗口内（800ms）再次评估同一应用 → 忽略，防重复弹窗
        assertFalse(gate.evaluate("com.ss.android.ugc.aweme", now = 500, globalEnabled = true, isTarget = true))
        // 去抖过期后 → 再次拦截
        assertTrue(gate.evaluate("com.ss.android.ugc.aweme", now = 1_000, globalEnabled = true, isTarget = true))
    }

    @Test
    fun `非目标应用不拦截`() {
        assertFalse(gate.evaluate("com.example.other", now = 0, globalEnabled = true, isTarget = false))
    }

    @Test
    fun `全局关闭时不拦截`() {
        assertFalse(gate.evaluate("com.xingin.xhs", now = 0, globalEnabled = false, isTarget = true))
    }

    @Test
    fun `抑制窗口内放行，避免打开后二次拦截`() {
        // 用户刚选「打开」→ armSuppression 5s
        gate.armSuppression("tv.danmaku.bili", now = 1_000)
        // 抑制窗口内（重启目标应用）→ 不拦截
        assertFalse(gate.evaluate("tv.danmaku.bili", now = 2_000, globalEnabled = true, isTarget = true))
        assertFalse(gate.evaluate("tv.danmaku.bili", now = 5_500, globalEnabled = true, isTarget = true))
        // 抑制过期后 → 恢复拦截
        assertTrue(gate.evaluate("tv.danmaku.bili", now = 7_000, globalEnabled = true, isTarget = true))
    }

    @Test
    fun `不同应用互不影响`() {
        assertTrue(gate.evaluate("com.ss.android.ugc.aweme", now = 0, globalEnabled = true, isTarget = true))
        // 抖音在去抖中，但小红书不受影响
        assertTrue(gate.evaluate("com.xingin.xhs", now = 100, globalEnabled = true, isTarget = true))
    }
}
