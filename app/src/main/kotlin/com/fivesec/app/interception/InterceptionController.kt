package com.fivesec.app.interception

import com.fivesec.app.data.datastore.SettingsDataStore
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * 决定是否拦截某个进入前台的应用。
 * 后台收集目标清单与全局开关快照，事件回调里只做纯内存判断（委托 [CooldownGate]），避免主线程 IO。
 */
@Singleton
class InterceptionController @Inject constructor(
    private val targetAppRepository: TargetAppRepository,
    private val settingsDataStore: SettingsDataStore,
    private val timeProvider: TimeProvider,
) {
    sealed interface Decision {
        data object Block : Decision
        data object Ignore : Decision
    }

    private val gate = CooldownGate(DEBOUNCE_MS, SUPPRESSION_MS)

    @Volatile private var globalEnabled: Boolean = true
    @Volatile private var enabledTargets: Set<String> = emptySet()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            targetAppRepository.observeAll().collect { apps ->
                enabledTargets = apps.filter { it.isEnabled }.map { it.packageName }.toSet()
            }
        }
        scope.launch {
            settingsDataStore.globalEnabled.collect { globalEnabled = it }
        }
    }

    fun evaluate(pkg: String): Decision {
        val now = timeProvider.now()
        val block = gate.evaluate(pkg, now, globalEnabled, pkg in enabledTargets)
        return if (block) Decision.Block else Decision.Ignore
    }

    fun armSuppression(pkg: String) = gate.armSuppression(pkg, timeProvider.now())

    /** 判断应用是否在拦截目标列表中 */
    fun isTarget(pkg: String): Boolean = pkg in enabledTargets

    companion object {
        const val DEBOUNCE_MS = 800L
        const val SUPPRESSION_MS = 5_000L
    }
}
