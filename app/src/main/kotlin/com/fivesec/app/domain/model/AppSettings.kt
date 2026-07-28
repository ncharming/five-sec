package com.fivesec.app.domain.model

/** 应用级设置（由 DataStore 持有，这里是 UI 用的聚合快照）。 */
data class AppSettings(
    val globalInterceptionEnabled: Boolean,
    val onboardingCompleted: Boolean,
    val statsRetentionDays: Int,
)
