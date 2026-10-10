package com.fivesec.app.interception

import com.fivesec.app.domain.model.UsageSessionEndReason
import com.fivesec.app.util.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [UsageSessionTracker] 状态机矩阵（specs/012）：装/取消计时、闲置惰性关闭、回弹上限、
 *  四种结束原因、覆盖层不可用降级——判定逻辑全在此，服务只跑腿不判定。 */
class UsageSessionTrackerTest {

    private class FakeTime(var nowMs: Long = 0L) : TimeProvider {
        override fun now(): Long = nowMs
    }

    private val time = FakeTime()
    private val tracker = UsageSessionTracker(time)
    private val pkg = "com.ss.android.ugc.aweme"
    private val other = "com.tencent.mm"
    private val minutes = 15
    private val minute = UsageSessionTracker.MILLIS_PER_MINUTE

    private fun arms(actions: List<UsageSessionTracker.Action>) =
        actions.filterIsInstance<UsageSessionTracker.Action.ArmTimer>()

    private fun closes(actions: List<UsageSessionTracker.Action>) =
        actions.filterIsInstance<UsageSessionTracker.Action.CloseSession>()

    @Test
    fun `打开后开会话并装计时，守护关时只开记录`() {
        // 守护开：装 15 分钟计时，无落库
        val opened = tracker.onOpened(pkg, minutes)
        assertEquals(listOf(UsageSessionTracker.Action.ArmTimer(minutes)), opened)

        // 守护关（null）：会话照开（记录用），永不装计时
        val time2 = FakeTime()
        val tracker2 = UsageSessionTracker(time2)
        assertEquals(emptyList<UsageSessionTracker.Action>(), tracker2.onOpened(pkg, null))
    }

    @Test
    fun `切走取消计时，切回重装整段而非续算剩余`() {
        tracker.onOpened(pkg, minutes)
        time.nowMs = 1 * minute
        // 切走：取消计时；不足闲置阈值不关会话
        assertEquals(
            listOf(UsageSessionTracker.Action.CancelTimer),
            tracker.onForegroundSwitched(other, minutes),
        )
        // 切回：重装整段 15 分钟（连续停留口径——从切回时刻重新计满，不续算剩余 14 分钟）
        time.nowMs = 2 * minute
        assertEquals(
            listOf(UsageSessionTracker.Action.ArmTimer(minutes)),
            tracker.onForegroundSwitched(pkg, minutes),
        )
    }

    @Test
    fun `离开满30分钟惰性关会话且时长止于最后停留`() {
        tracker.onOpened(pkg, minutes)
        time.nowMs = 2 * minute
        tracker.onForegroundSwitched(pkg, minutes) // 停留中刷新最后停留时刻
        time.nowMs = 3 * minute
        tracker.onForegroundSwitched(other, minutes) // 切走（<30 分钟不关）
        // 另一非会话应用的事件在 40 分钟到达：40-2=38 分钟 ≥30 → 惰性关闭
        time.nowMs = 40 * minute
        val actions = tracker.onForegroundSwitched(other, minutes)
        val close = closes(actions).single()
        assertEquals(pkg, close.record.packageName) // 关的是会话应用
        assertEquals(UsageSessionEndReason.IDLE_TIMEOUT, close.record.endReason)
        assertEquals(2 * minute, close.record.endedAt) // endedAt=最后停留时刻，不含离开后时间
        assertEquals(2 * minute, close.record.durationMillis)
    }

    @Test
    fun `到点前台已切走不回弹，仍在会话应用才回弹`() {
        tracker.onOpened(pkg, minutes)
        time.nowMs = 1 * minute
        tracker.onForegroundSwitched(other, minutes) // 切走取消计时
        // 竞速次序兜底：计时到点但前台已非会话应用 → 静默
        assertNull(tracker.onTimerFired(other))

        // 正常路径：仍在会话应用 → 回弹（elapsed 分钟自打开墙钟向上取整）
        time.nowMs = 15 * minute
        tracker.onForegroundSwitched(pkg, minutes) // 切回重装
        time.nowMs = 30 * minute
        val fired = tracker.onTimerFired(pkg)
        assertEquals(pkg, fired?.packageName)
        assertEquals(30L, fired?.elapsedMinutes)
    }

    @Test
    fun `回弹三次后本场静默且结束原因记录次数`() {
        tracker.onOpened(pkg, minutes)
        // 前两次：到点回弹 → 继续后重装计时
        repeat(2) { index ->
            time.nowMs = (15 * (index + 1)) * minute
            val fired = tracker.onTimerFired(pkg)
            assertEquals(pkg, fired?.packageName)
            assertEquals(
                listOf(UsageSessionTracker.Action.ArmTimer(minutes)),
                tracker.onGuardContinue(minutes),
            )
        }
        // 第 3 次弹出后选继续：达上限（3 次/场）→ 不再装计时
        time.nowMs = 45 * minute
        assertEquals(pkg, tracker.onTimerFired(pkg)?.packageName)
        assertEquals(emptyList<UsageSessionTracker.Action>(), tracker.onGuardContinue(minutes))
        // 之后到点静默
        time.nowMs = 60 * minute
        assertNull(tracker.onTimerFired(pkg))
        // 结束落库：guardShownCount=3
        time.nowMs = 61 * minute
        val close = closes(tracker.onGuardEnded()).single()
        assertEquals(UsageSessionEndReason.GUARD_ENDED, close.record.endReason)
        assertEquals(3, close.record.guardShownCount)
        assertEquals(61 * minute, close.record.durationMillis)
    }

    @Test
    fun `继续时守护已关则纯撤层不装计时`() {
        tracker.onOpened(pkg, minutes)
        time.nowMs = 15 * minute
        tracker.onTimerFired(pkg)
        assertEquals(emptyList<UsageSessionTracker.Action>(), tracker.onGuardContinue(null))
    }

    @Test
    fun `结束落GUARD_ENDED且时长为墙钟`() {
        tracker.onOpened(pkg, minutes)
        time.nowMs = 15 * minute
        tracker.onTimerFired(pkg)
        time.nowMs = 16 * minute
        val close = closes(tracker.onGuardEnded()).single()
        assertEquals(UsageSessionEndReason.GUARD_ENDED, close.record.endReason)
        assertEquals(16 * minute, close.record.endedAt)
        assertEquals(16 * minute, close.record.durationMillis)
        assertEquals(1, close.record.guardShownCount)
    }

    @Test
    fun `新会话开启时旧会话落SUPERSEDED`() {
        tracker.onOpened(pkg, minutes)
        time.nowMs = 1 * minute
        tracker.onForegroundSwitched(pkg, minutes) // 刷新最后停留=1 分钟
        time.nowMs = 2 * minute
        val actions = tracker.onOpened(other, minutes) // 换目标重开：旧会话被取代
        val close = closes(actions).single()
        assertEquals(pkg, close.record.packageName)
        assertEquals(UsageSessionEndReason.SUPERSEDED, close.record.endReason)
        assertEquals(1 * minute, close.record.endedAt) // endedAt=旧会话最后停留时刻
        assertEquals(1, arms(actions).size) // 新会话照装计时
    }

    @Test
    fun `覆盖层不可用降级为本场停守护`() {
        tracker.onOpened(pkg, minutes)
        val actions = tracker.onGuardUnavailable()
        assertEquals(listOf(UsageSessionTracker.Action.CancelTimer), actions)
        time.nowMs = 15 * minute
        assertNull(tracker.onTimerFired(pkg)) // 不再回弹
        assertEquals(emptyList<UsageSessionTracker.Action>(), tracker.onGuardContinue(minutes))
        // 会话照常记录到正常结束
        time.nowMs = 20 * minute
        val close = closes(tracker.onGuardEnded()).single()
        assertEquals(0, close.record.guardShownCount)
        assertEquals(UsageSessionEndReason.GUARD_ENDED, close.record.endReason)
    }

    @Test
    fun `服务停止兜底落SERVICE_STOPPED且时长止于最后停留`() {
        tracker.onOpened(pkg, minutes)
        time.nowMs = 1 * minute
        tracker.onForegroundSwitched(pkg, minutes)
        time.nowMs = 10 * minute
        val close = closes(tracker.onServiceStopped()).single()
        assertEquals(UsageSessionEndReason.SERVICE_STOPPED, close.record.endReason)
        assertEquals(1 * minute, close.record.endedAt)
        assertEquals(1 * minute, close.record.durationMillis)
    }

    @Test
    fun `无会话时各入口全部空返回`() {
        assertEquals(emptyList<UsageSessionTracker.Action>(), tracker.onForegroundSwitched(pkg, minutes))
        assertNull(tracker.onTimerFired(pkg))
        assertEquals(emptyList<UsageSessionTracker.Action>(), tracker.onGuardContinue(minutes))
        assertEquals(emptyList<UsageSessionTracker.Action>(), tracker.onGuardEnded())
        assertEquals(emptyList<UsageSessionTracker.Action>(), tracker.onServiceStopped())
    }
}
