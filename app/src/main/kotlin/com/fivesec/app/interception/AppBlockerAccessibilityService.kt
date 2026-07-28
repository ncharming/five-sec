package com.fivesec.app.interception

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.fivesec.app.blocking.BlockingOverlay
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
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
    private var currentForegroundPkg: String? = null // 跟踪当前前台应用
    private var suppressedPkg: String? = null // 用户选择"打开"后抑制该应用的所有窗口事件
    private var userOpenedPkg: String? = null // 用户主动选择"打开"的应用，在该应用使用期间永久放行

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName) return // 忽略自身窗口

        // 检测是否切换到不同应用（不包括内部窗口变化）
        val appSwitched = pkg != currentForegroundPkg
        if (appSwitched) {
            val previousForegroundPkg = currentForegroundPkg
            currentForegroundPkg = pkg
            // 如果切换到其他非目标应用，取消暂时抑制状态（但保留用户主动打开的标记）
            if (pkg != suppressedPkg && pkg != userOpenedPkg) {
                suppressedPkg = null
            }
            // 如果用户主动切换到另一个目标应用，清除之前的 userOpenedPkg 标记
            // 这样可以确保只有当前正在使用的目标应用才不会被拦截
            val currentIsTarget = controller.isTarget(pkg)
            if (currentIsTarget && pkg != userOpenedPkg && previousForegroundPkg != userOpenedPkg) {
                userOpenedPkg = null
            }
        }

        // 如果用户主动打开了该应用，则忽略其所有窗口事件
        if (pkg == userOpenedPkg) {
            return
        }
        // 如果当前应用被暂时抑制（用户选择"打开"后的短暂期间），则忽略其窗口事件
        if (pkg == suppressedPkg) {
            return
        }

        if (currentOverlay != null) return // 覆盖层显示中：忽略一切后续事件，避免倒计时期间自中断
        val decision = controller.evaluate(pkg)
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
                userOpenedPkg = pkg // 永久标记该应用为用户主动打开，在使用期间不再拦截
                suppressedPkg = pkg // 同时设置暂时抑制状态，用于立即生效
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
