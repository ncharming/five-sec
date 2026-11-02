package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.datastore.SettingsDataStore
import com.fivesec.app.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = settingsDataStore.settings.stateIn(
        viewModelScope,
        // VM 随返回栈常驻：无 UI 订阅即停（2026-11 与 Eagerly 约定一并修订）
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        AppSettings(
            globalInterceptionEnabled = true,
            onboardingCompleted = false,
            statsRetentionDays = 90,
        ),
    )

    fun setGlobalEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setGlobalEnabled(enabled) }
    }

    /** 全局回弹间隔（specs/012）：夹取在仓库 setter 内兜底，这里直接透传。 */
    fun setSessionGuardMinutes(minutes: Int) {
        viewModelScope.launch { settingsDataStore.setSessionGuardMinutes(minutes) }
    }

    companion object {
        /** 无 UI 订阅后流的存活余量（切 Tab/瞬断往返不清空画面、长离场即停）。 */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
