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

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val GLOBAL_ENABLED = booleanPreferencesKey("interception_globally_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val RETENTION_DAYS = intPreferencesKey("stats_retention_days")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            globalInterceptionEnabled = p[Keys.GLOBAL_ENABLED] ?: true,
            onboardingCompleted = p[Keys.ONBOARDING_DONE] ?: false,
            statsRetentionDays = p[Keys.RETENTION_DAYS] ?: 90,
        )
    }

    val globalEnabled: Flow<Boolean> = settings.map { it.globalInterceptionEnabled }

    suspend fun setGlobalEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.GLOBAL_ENABLED] = enabled }
    }

    suspend fun setOnboardingCompleted(done: Boolean) {
        context.dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }
}
