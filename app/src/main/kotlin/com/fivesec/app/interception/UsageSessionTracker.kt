package com.fivesec.app.interception

import com.fivesec.app.domain.model.UsageSessionEndReason
import com.fivesec.app.util.TimeProvider

/**
 * 使用时长守护会话状态机（specs/012，纯 Kotlin 零 Android 依赖）。
 *
 * 服务只做事件路由与本类返回的 [Action] 执行（计时/落库）——判定、记账、上限、闲置关闭全在这。
 * 时间一律经注入 [TimeProvider]（可测）；guardMinutes 为 null 表示该应用守护关闭（或配置查不到）：
 * 会话照开照记，只是永不装计时。
 *
 * 计时口径=「连续停留」：计时只在「前台==会话应用」期间有效——切走即取消，切回重装整段 N 分钟
 * （非剩余累计）；到点时由服务以真实前台终验（[onTimerFired] 传 foregroundPkg），竞速次序兜底。
 */
class UsageSessionTracker(private val timeProvider: TimeProvider) {

    /** 服务要执行的动作（顺序保证：CloseSession 先于 ArmTimer 返回时，服务按序执行）。 */
    sealed interface Action {
        /** 装（重装）回弹计时：delay = minutes 分钟后回调 onTimerFired。 */
        data class ArmTimer(val minutes: Int) : Action

        /** 取消当前回弹计时。 */
        data object CancelTimer : Action

        /** 会话结束：异步落一行 usage_sessions。 */
        data class CloseSession(val record: UsageSessionRecord) : Action
    }

    /** 计时到点且可回弹：弹回弹层所需信息。 */
    data class TimerFired(val packageName: String, val elapsedMinutes: Long)

    /** usage_sessions 落库行（服务转 UsageSession 实体）。 */
    data class UsageSessionRecord(
        val packageName: String,
        val startedAt: Long,
        val endedAt: Long,
        val durationMillis: Long,
        val guardShownCount: Int,
        val endReason: UsageSessionEndReason,
    )

    private class Session(
        val pkg: String,
        val startedAt: Long,
        var lastForegroundAt: Long,
        var guardShownCount: Int = 0,
        var timerArmed: Boolean = false, // 当前是否有已装计时（切走取消、切回/继续重装）
        var guarding: Boolean = true,    // 本场是否仍守护（达回弹上限或覆盖层不可用后置假）
    )

    private var session: Session? = null

    /** 拦截页选「打开」：开（或切换）会话；guardMinutes=null（守护关）只开记录不装计时。 */
    fun onOpened(pkg: String, guardMinutes: Int?): List<Action> {
        val now = timeProvider.now()
        val actions = mutableListOf<Action>()
        session?.let { actions += closeSession(it, it.lastForegroundAt, UsageSessionEndReason.SUPERSEDED) }
        val s = Session(pkg, now, now)
        session = s
        if (guardMinutes != null) {
            s.timerArmed = true
            actions += Action.ArmTimer(guardMinutes)
        }
        return actions
    }

    /**
     * 前台应用切换（appSwitched 时路由，pkg=新前台；guardMinutes=该应用当前配置，运行中变更
     * 在下次装计时点生效——已装计时不追改）。
     */
    fun onForegroundSwitched(pkg: String, guardMinutes: Int?): List<Action> {
        val s = session ?: return emptyList()
        val now = timeProvider.now()
        if (pkg == s.pkg) {
            s.lastForegroundAt = now
            return if (!s.timerArmed && s.guarding && guardMinutes != null) {
                // 切回会话应用：重装整段计时（连续停留口径，不续算剩余）
                s.timerArmed = true
                listOf(Action.ArmTimer(guardMinutes))
            } else {
                emptyList()
            }
        }
        // 切走：取消计时；闲置达阈值则关会话（endedAt=最后停留时刻——时长不含离开后时间）
        val actions = mutableListOf<Action>(Action.CancelTimer)
        s.timerArmed = false
        if (now - s.lastForegroundAt >= IDLE_CLOSE_MS) {
            actions += closeSession(s, s.lastForegroundAt, UsageSessionEndReason.IDLE_TIMEOUT)
            session = null
        }
        return actions
    }

    /** 计时到点（服务回调）：前台已切走或本场不再守护→静默；否则计一次回弹并要求弹层。 */
    fun onTimerFired(foregroundPkg: String?): TimerFired? {
        val s = session ?: return null
        s.timerArmed = false
        if (foregroundPkg != s.pkg || !s.guarding) return null
        s.guardShownCount += 1
        if (s.guardShownCount >= MAX_GUARD_PER_SESSION) s.guarding = false
        val now = timeProvider.now()
        s.lastForegroundAt = now // 前台终验已过：此刻确在会话应用
        return TimerFired(s.pkg, elapsedMinutes(now, s.startedAt))
    }

    /** 回弹层选「继续」：守护中→重装计时（达上限则本场静默）；守护关→纯撤层。 */
    fun onGuardContinue(guardMinutes: Int?): List<Action> {
        val s = session ?: return emptyList()
        return if (s.guarding && guardMinutes != null) {
            s.timerArmed = true
            listOf(Action.ArmTimer(guardMinutes))
        } else {
            emptyList()
        }
    }

    /** 回弹层选「结束」：关会话（GUARD_ENDED，endedAt=now）——HOME/撤层/清标记由服务执行。 */
    fun onGuardEnded(): List<Action> {
        val s = session ?: return emptyList()
        val action = closeSession(s, timeProvider.now(), UsageSessionEndReason.GUARD_ENDED)
        session = null
        return listOf(action)
    }

    /** 回弹层覆盖层创建失败：本场停守护（不重试不闪退），会话记录照走到正常结束。 */
    fun onGuardUnavailable(): List<Action> {
        val s = session ?: return emptyList()
        s.guarding = false
        s.timerArmed = false
        return listOf(Action.CancelTimer)
    }

    /** 服务 onUnbind/onDestroy 兜底：尽力落 SERVICE_STOPPED（进程被杀则整行丢失，可接受）。 */
    fun onServiceStopped(): List<Action> {
        val s = session ?: return emptyList()
        val action = closeSession(s, s.lastForegroundAt, UsageSessionEndReason.SERVICE_STOPPED)
        session = null
        return listOf(action)
    }

    private fun closeSession(s: Session, endedAt: Long, reason: UsageSessionEndReason) =
        Action.CloseSession(
            UsageSessionRecord(
                packageName = s.pkg,
                startedAt = s.startedAt,
                endedAt = endedAt,
                durationMillis = (endedAt - s.startedAt).coerceAtLeast(0),
                guardShownCount = s.guardShownCount,
                endReason = reason,
            )
        )

    /** 标题分钟数：自 startedAt 墙钟向上取整（15:00:01 → 16）。 */
    private fun elapsedMinutes(now: Long, startedAt: Long): Long =
        (now - startedAt + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE

    companion object {
        const val MILLIS_PER_MINUTE = 60_000L

        /** 闲置关闭阈值：离开 ≥30 分钟视为会话事实死亡（惰性判定，无后台计时器）。 */
        const val IDLE_CLOSE_MS = 30 * MILLIS_PER_MINUTE

        /** 每场会话回弹上限：第 3 次选继续后本场静默（提醒不是管制）。 */
        const val MAX_GUARD_PER_SESSION = 3
    }
}
