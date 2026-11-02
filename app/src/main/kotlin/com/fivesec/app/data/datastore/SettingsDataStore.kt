package com.fivesec.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fivesec.app.domain.model.AppSettings
import com.fivesec.app.util.StayBudget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "five_sec_settings")

/**
 * 应用设置存取口（DataStore Preferences）。提示语相关键（`builtin_hints_enabled`/
 * `hint_cycle_cursor`）随 specs/009-retire-hints 退役：代码全部移除，设备上的残留键值
 * 不清理、无读取者（FR-007 允许残留）。
 */
@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val GLOBAL_ENABLED = booleanPreferencesKey("interception_globally_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val RETENTION_DAYS = intPreferencesKey("stats_retention_days")
        val SESSION_GUARD_MINUTES = intPreferencesKey("session_guard_minutes") // specs/012：全局回弹间隔
        val STAY_BUDGET_MINUTES = intPreferencesKey("stay_budget_minutes") // specs/015：每日停留预算（0=不设）
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            globalInterceptionEnabled = p[Keys.GLOBAL_ENABLED] ?: true,
            onboardingCompleted = p[Keys.ONBOARDING_DONE] ?: false,
            statsRetentionDays = p[Keys.RETENTION_DAYS] ?: 90,
            // 夹取防御：异常写入（如直改文件）不会让间隔跳出合法区间
            sessionGuardMinutes = (p[Keys.SESSION_GUARD_MINUTES] ?: DEFAULT_SESSION_GUARD_MINUTES)
                .coerceIn(MIN_SESSION_GUARD_MINUTES, MAX_SESSION_GUARD_MINUTES),
            // 同一夹取口径：0=不设，上限与守护间隔同量级（见 StayBudget）
            stayBudgetMinutes = (p[Keys.STAY_BUDGET_MINUTES] ?: DEFAULT_STAY_BUDGET_MINUTES)
                .coerceIn(0, StayBudget.MAX_BUDGET_MINUTES),
        )
    }

    val globalEnabled: Flow<Boolean> = settings.map { it.globalInterceptionEnabled }

    suspend fun setGlobalEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GLOBAL_ENABLED] = enabled }
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    suspend fun setSessionGuardMinutes(minutes: Int) {
        context.dataStore.edit {
            it[Keys.SESSION_GUARD_MINUTES] = minutes.coerceIn(MIN_SESSION_GUARD_MINUTES, MAX_SESSION_GUARD_MINUTES)
        }
    }

    /** 每日停留预算（specs/015）：0=不设；夹取口径与读取侧一致。 */
    suspend fun setStayBudgetMinutes(minutes: Int) {
        context.dataStore.edit {
            it[Keys.STAY_BUDGET_MINUTES] = minutes.coerceIn(0, StayBudget.MAX_BUDGET_MINUTES)
        }
    }

    companion object {
        const val DEFAULT_SESSION_GUARD_MINUTES = 15
        const val MIN_SESSION_GUARD_MINUTES = 5
        const val MAX_SESSION_GUARD_MINUTES = 60

        /** 预算默认不设（specs/015 拍板：不给存量用户加未经同意的新约束感）。 */
        const val DEFAULT_STAY_BUDGET_MINUTES = 0
    }
}
