package com.fivesec.app.interception

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.fivesec.app.blocking.BlockingActivity
import com.fivesec.app.util.DebugLog
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 通过 TYPE_WINDOW_STATE_CHANGED 检测目标应用进入前台：
 *  命中 → performGlobalAction(HOME) 隐藏目标 → 启动 BlockingActivity 拦截页。
 * AccessibilityService 由系统创建，无法用 @AndroidEntryPoint，故用 EntryPoint 取依赖。
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface InterceptionEntryPoint {
        fun controller(): InterceptionController
    }

    private val controller: InterceptionController by lazy {
        EntryPointAccessors.fromApplication(applicationContext, InterceptionEntryPoint::class.java).controller()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isEmpty() || pkg == packageName) return // 忽略自身窗口
        val decision = controller.evaluate(pkg)
        DebugLog.log(applicationContext, "[DBG-FS] event pkg=$pkg decision=$decision")
        if (decision is InterceptionController.Decision.Block) {
            val homeOk = performGlobalAction(GLOBAL_ACTION_HOME)
            DebugLog.log(applicationContext, "[DBG-FS] HOME returned=$homeOk; calling startActivity(BlockingActivity) for $pkg")
            try {
                startActivity(BlockingActivity.intent(this, pkg))
                DebugLog.log(applicationContext, "[DBG-FS] startActivity returned OK for $pkg")
            } catch (t: Throwable) {
                DebugLog.log(applicationContext, "[DBG-FS] startActivity THREW for $pkg", t)
            }
        }
    }

    override fun onInterrupt() = Unit
}
