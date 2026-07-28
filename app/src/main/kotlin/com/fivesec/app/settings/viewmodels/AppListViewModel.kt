package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.domain.model.TargetApp
import com.fivesec.app.util.PackageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class AppListViewModel @Inject constructor(
    @ApplicationContext private val app: android.content.Context,
    private val targetAppRepository: TargetAppRepository,
) : ViewModel() {

    val targetApps: StateFlow<List<TargetApp>> =
        targetAppRepository.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun installedApps(): List<PackageUtil.InstalledApp> =
        PackageUtil.installedUserApps(app.packageManager)

    fun add(packageName: String, now: Long) {
        viewModelScope.launch {
            // 获取应用名称
            val appName = PackageUtil.label(app.packageManager, packageName)

            // 使用新的 addNewApp 方法，包含 3 个应用限制检查
            val result = targetAppRepository.addNewApp(
                TargetApp(
                    packageName = packageName,
                    appName = appName,
                    isEnabled = true,
                    isDefault = false,
                    addedAt = now
                )
            )

            // 处理结果（可以添加错误提示UI）
            result.onFailure { error ->
                // 这里可以添加错误提示逻辑
                android.util.Log.e("AppListViewModel", "Failed to add app: ${error.message}")
            }
        }
    }

    fun remove(packageName: String) {
        viewModelScope.launch { targetAppRepository.remove(packageName) }
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch { targetAppRepository.setEnabled(packageName, enabled) }
    }
}