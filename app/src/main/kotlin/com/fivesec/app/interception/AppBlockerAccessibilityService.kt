package com.fivesec.app.interception

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.fivesec.app.blocking.BlockingOverlay
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.util.DebugLog
import com.fivesec.app.util.PackageUtil
import com.fivesec.app.util.TimeProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 通过 TYPE_WINDOW_STATE_CHANGED 检测目标应用进入前台：
 *  命中 → 直接绘制全屏 TYPE_ACCESSIBILITY_OVERLAY 拦截覆盖层（[BlockingOverlay]）。
 * 用覆盖层而非 Activity，规避 OEM（如 ColorOS）对"后台 startActivity"的静默拦截。
 * AccessibilityService 由系统创建，无法用 @AndroidEntryPoint，故用 EntryPoint 取依赖。
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface InterceptionEntryPoint {
        fun controller(): InterceptionController
        fun repository(): InterceptionRepository
        fun timeProvider(): TimeProvider
        fun appScope(): CoroutineScope
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, InterceptionEntryPoint::class.java)
    }
    private val controller by lazy { entryPoint.controller() }
    private val repository by lazy { entryPoint.repository() }
    private val timeProvider by lazy { entryPoint.timeProvider() }
    private val appScope by lazy { entryPoint.appScope() }

    private var currentOverlay: BlockingOverlay? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName) return // 忽略自身窗口
        if (currentOverlay != null) return // 覆盖层显示中：忽略一切后续事件，避免倒计时期间自中断
        val decision = controller.evaluate(pkg)
        DebugLog.log(applicationContext, "[DBG-FS] event pkg=$pkg decision=$decision")
        if (decision is InterceptionController.Decision.Block) {
            val appLabel = PackageUtil.label(packageManager, pkg)
            val overlay = BlockingOverlay(this, appLabel) { outcome ->
                onBlockingFinished(pkg, outcome)
            }
            currentOverlay = overlay
            overlay.show()
        }
    }

    private fun onBlockingFinished(pkg: String, outcome: InterceptionOutcome) {
        DebugLog.log(applicationContext, "[DBG-FS] onBlockingFinished pkg=$pkg outcome=$outcome")
        val overlay = currentOverlay
        currentOverlay = null
        val completed = outcome != InterceptionOutcome.INTERRUPTED
        appScope.launch {
            repository.record(
                InterceptionEvent(
                    packageName = pkg,
                    timestamp = timeProvider.now(),
                    exerciseCompleted = completed,
                    outcome = outcome,
                )
            )
        }
        when (outcome) {
            InterceptionOutcome.OPENED -> {
                controller.armSuppression(pkg)
                overlay?.dismiss() // 目标一直在覆盖层后运行，移除即见
            }
            InterceptionOutcome.CANCELED, InterceptionOutcome.INTERRUPTED -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                overlay?.dismiss(delayMs = 250) // 先回桌面再撤覆盖层，避免目标闪现
            }
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        currentOverlay?.dismiss()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        currentOverlay?.dismiss()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit
}
