package com.fivesec.app.settings.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.domain.model.TargetApp
import com.fivesec.app.util.PackageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class AppListViewModel @Inject constructor(
    @ApplicationContext private val app: android.content.Context,
    private val targetAppRepository: TargetAppRepository,
) : ViewModel() {

    val targetApps: StateFlow<List<TargetApp>> =
        targetAppRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** 添加失败一次性事件（中文文案来自仓库，null=未知原因由 UI 兜底资源呈现）：
     *  2026-11 前仅 Log.e 无用户提示——名额满/写库失败的反馈必须可见。 */
    private val _addFailure = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val addFailure: SharedFlow<String?> = _addFailure.asSharedFlow()

    private val _installedApps = MutableStateFlow<List<PackageUtil.InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<PackageUtil.InstalledApp>> = _installedApps.asStateFlow()

    init {
        // 枚举已安装应用较重（逐个 getApplicationLabel），放到 IO 线程并缓存，避免主线程卡顿。
        viewModelScope.launch {
            _installedApps.value = withContext(Dispatchers.IO) {
                PackageUtil.installedUserApps(app.packageManager)
            }
        }
    }

    fun add(packageName: String, now: Long) {
        viewModelScope.launch {
            // 应用名查询是 PackageManager binder 调用，挪 IO（2026-11：此前在 Main）；查不到兜底包名
            val appName = withContext(Dispatchers.IO) {
                runCatching { PackageUtil.label(app.packageManager, packageName) }.getOrDefault(packageName)
            }

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

            result.onFailure { error -> _addFailure.tryEmit(error.message) }
        }
    }

    fun remove(packageName: String) {
        viewModelScope.launch { targetAppRepository.remove(packageName) }
    }

    fun setEnabled(packageName: String, enabled: Boolean) {
        viewModelScope.launch { targetAppRepository.setEnabled(packageName, enabled) }
    }

    /** 每应用会话守护开关（specs/012）：关=该应用会话永不回弹（会话记录照写）。 */
    fun setSessionGuard(packageName: String, enabled: Boolean) {
        viewModelScope.launch { targetAppRepository.setSessionGuardEnabled(packageName, enabled) }
    }

    companion object {
        /** 无 UI 订阅后查询链的存活余量（VM 随返回栈常驻，2026-11 与 Eagerly 约定一并修订）。 */
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}