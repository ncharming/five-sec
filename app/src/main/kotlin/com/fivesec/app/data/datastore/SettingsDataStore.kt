package com.fivesec.app.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fivesec.app.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "five_sec_settings")

/**
 * 内置提示语开关的持久化存取口（specs：提示语页内置条目启停）：
 * 生产实现是 [SettingsDataStore]（DataStore Preferences，项目现有配置存储），
 * 抽成接口是为了让 HintRepository / HintListViewModel 在 JVM 单测里用手控 fake 替身——
 * 具体 DataStore 依赖 Context 与磁盘文件，无法在纯 JUnit 测试中安全构造。
 */
interface BuiltinHintsSetting {
    /** 内置提示语是否参与随机抽取；首次使用（无历史配置）按 true 回落。 */
    val builtinHintsEnabled: Flow<Boolean>

    suspend fun setBuiltinHintsEnabled(enabled: Boolean)
}

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : BuiltinHintsSetting {
    private object Keys {
        val GLOBAL_ENABLED = booleanPreferencesKey("interception_globally_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val RETENTION_DAYS = intPreferencesKey("stats_retention_days")
        val BUILTIN_HINTS_ENABLED = booleanPreferencesKey("builtin_hints_enabled")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            globalInterceptionEnabled = p[Keys.GLOBAL_ENABLED] ?: true,
            onboardingCompleted = p[Keys.ONBOARDING_DONE] ?: false,
            statsRetentionDays = p[Keys.RETENTION_DAYS] ?: 90,
            builtinHintsEnabled = p[Keys.BUILTIN_HINTS_ENABLED] ?: true, // 首次使用默认开启 = 现行为
        )
    }

    val globalEnabled: Flow<Boolean> = settings.map { it.globalInterceptionEnabled }

    override val builtinHintsEnabled: Flow<Boolean> = settings.map { it.builtinHintsEnabled }

    suspend fun setGlobalEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GLOBAL_ENABLED] = enabled }
    }

    override suspend fun setBuiltinHintsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.BUILTIN_HINTS_ENABLED] = enabled }
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }
}
