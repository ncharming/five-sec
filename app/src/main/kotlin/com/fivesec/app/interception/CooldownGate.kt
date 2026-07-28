package com.fivesec.app.interception

import java.util.concurrent.ConcurrentHashMap

/**
 * 纯逻辑：去抖 + 抑制窗口判定，不含 Android/IO 依赖，便于单元测试。
 *  - 去抖：命中目标后设置 [debounceMs] 冷却，防止 TYPE_WINDOW_STATE_CHANGED 连发导致重复弹窗
 *  - 抑制：armSuppression 设置 [suppressionMs] 冷却，让"打开"后的目标重启不被再次拦截
 */
class CooldownGate(
    private val debounceMs: Long,
    private val suppressionMs: Long,
) {
    private val cooldowns = ConcurrentHashMap<String, Long>()

    /** 是否应拦截。命中并放行时写入去抖冷却。 */
    fun evaluate(pkg: String, now: Long, globalEnabled: Boolean, isTarget: Boolean): Boolean {
        cooldowns[pkg]?.let { if (now < it) return false }
        if (!globalEnabled || !isTarget) return false
        cooldowns[pkg] = now + debounceMs
        return true
    }

    /** 用户选「打开」后临时放行该应用的重新启动。 */
    fun armSuppression(pkg: String, now: Long) {
        cooldowns[pkg] = now + suppressionMs
    }

    fun isCoolingDown(pkg: String, now: Long): Boolean = cooldowns[pkg]?.let { now < it } ?: false
}
