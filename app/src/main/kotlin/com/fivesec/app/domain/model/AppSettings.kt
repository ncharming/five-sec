package com.fivesec.app.domain.model

/** 应用级设置（由 DataStore 持有，这里是 UI 用的聚合快照）。 */
data class AppSettings(
    val globalInterceptionEnabled: Boolean,
    val onboardingCompleted: Boolean,
    val statsRetentionDays: Int,
    // 全局回弹间隔（分钟，specs/012）：每应用守护开关在 target_apps，间隔只有这一个全局值
    val sessionGuardMinutes: Int = 15,
)
