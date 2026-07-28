package com.fivesec.app.settings.viewmodels

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.domain.model.TargetApp
import com.fivesec.app.util.PackageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppListViewModel @Inject constructor(
    private val app: Application,
    private val targetAppRepository: TargetAppRepository,
) : ViewModel() {

    val targetApps: StateFlow<List<TargetApp>> =
        targetAppRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun installedApps(): List<PackageUtil.InstalledApp> =
        PackageUtil.installedUserApps(app.packageManager)

    fun add(packageName: String, now: Long) {
        viewModelScope.launch {
            targetAppRepository.upsert(TargetApp(packageName = packageName, isEnabled = true, isDefault = false, addedAt = now))
        }
    }

    fun remove(packageName: String) {
        viewModelScope.launch { targetAppRepository.remove(packageName) }
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch { targetAppRepository.setEnabled(packageName, enabled) }
    }
}
