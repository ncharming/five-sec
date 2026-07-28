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

    val settings: StateFlow<AppSettings?> =
        settingsDataStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setGlobalEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setGlobalEnabled(enabled) }
    }
}
