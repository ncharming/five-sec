package com.fivesec.app.interception

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.fivesec.app.blocking.BlockingOverlay
import com.fivesec.app.blocking.UsageGuardOverlay
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.data.repository.UsageSessionRepository
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.UsageSession
import com.fivesec.app.util.DateUtil
import com.fivesec.app.util.PackageUtil
import com.fivesec.app.util.TimeProvider
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 通过 TYPE_WINDOW_STATE_CHANGED 检测目标应用进入前台：
 *  命中 → 直接绘制全屏 TYPE_ACCESSIBILITY_OVERLAY 拦截覆盖层（[BlockingOverlay]）。
 * 用覆盖层而非 Activity，规避 OEM（如 ColorOS）对"后台 startActivity"的静默拦截。
 * AccessibilityService 由系统创建，无法用 @AndroidEntryPoint，故用 EntryPoint 取依赖。
 *
 * 使用时长守护（specs/012）：选「打开」同时开启会话——前台切换事件路由给 [UsageSessionTracker]
 * 纯状态机（appSwitched 块末尾、一切放行早退之前——切走/切回都要记账），服务只执行其返回的
 * Action（coroutine 计时 / 异步落库 / 弹 [UsageGuardOverlay] 回弹层）。三个放行标记语义零改动。
 */
class AppBlockerAccessibilityService : AccessibilityService() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface InterceptionEntryPoint {
        fun controller(): InterceptionController
        fun repository(): InterceptionRepository
        fun todoRepository(): TodoRepository
        fun usageSessionRepository(): UsageSessionRepository
        fun timeProvider(): TimeProvider
        fun appScope(): CoroutineScope
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, InterceptionEntryPoint::class.java)
    }
    private val controller by lazy { entryPoint.controller() }
    private val repository by lazy { entryPoint.repository() }
    private val todoRepository by lazy { entryPoint.todoRepository() }
    private val usageSessionRepository by lazy { entryPoint.usageSessionRepository() }
    private val timeProvider by lazy { entryPoint.timeProvider() }
    private val appScope by lazy { entryPoint.appScope() }

    private var currentOverlay: BlockingOverlay? = null
    private var currentForegroundPkg: String? = null // 跟踪当前前台应用
    private var suppressedPkg: String? = null // 用户选择"打开"后抑制该应用的所有窗口事件
    private var userOpenedPkg: String? = null // 用户主动选择"打开"的应用，在该应用使用期间永久放行

    // 使用时长守护（specs/012）：回弹层槽位（与拦截覆盖层互斥）、回弹计时任务、会话状态机
    private var guardOverlay: UsageGuardOverlay? = null
    private var sessionJob: Job? = null
    private val sessionTracker by lazy { UsageSessionTracker(timeProvider) }

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
            // 使用时长守护（specs/012）：前台切换路由给会话状态机——必须在一切放行/覆盖层早退之前
            // （切走取消计时、切回重装、闲置惰性关闭都要记账）；配置运行中变更在下次装计时点生效
            handleSessionActions(sessionTracker.onForegroundSwitched(pkg, controller.sessionGuardMinutes(pkg)))
        }

        // 如果用户主动打开了该应用，则忽略其所有窗口事件
        if (pkg == userOpenedPkg) {
            return
        }
        // 如果当前应用被暂时抑制（用户选择"打开"后的短暂期间），则忽略其窗口事件
        if (pkg == suppressedPkg) {
            return
        }

        if (currentOverlay != null || guardOverlay != null) return // 覆盖层显示中（含回弹层）：忽略一切后续事件，避免倒计时期间自中断/双层叠窗
        val decision = controller.evaluate(pkg)
        if (decision is InterceptionController.Decision.Block) {
            val appLabel = PackageUtil.label(packageManager, pkg)
            // 今日待办快照：覆盖层创建瞬间定格（specs/005；009 起待办为唯一缓冲内容，提示语链路已退役）
            val todos = todoRepository.todayTodos(DateUtil.todayString(timeProvider.now()))
            val overlay = BlockingOverlay(
                context = this,
                appLabel = appLabel,
                todos = todos,
                // 今日抵制序号（specs/011）：求值时才取当天——覆盖层跨零点的极端场景也归零正确
                resistCountProvider = { repository.nextResistedOrdinal(DateUtil.todayString(timeProvider.now())) },
                onFinished = { outcome -> onBlockingFinished(pkg, outcome) },
            )
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
                // 使用时长守护（specs/012）：选「打开」即开会话；守护关（null）只开记录不装计时
                handleSessionActions(sessionTracker.onOpened(pkg, controller.sessionGuardMinutes(pkg)))
            }
            InterceptionOutcome.CANCELED, InterceptionOutcome.INTERRUPTED -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
                overlay?.dismiss(delayMs = 250) // 先回桌面再撤覆盖层，避免目标闪现
            }
        }
    }

    // ── 使用时长守护（specs/012）：会话动作执行——判定全在 UsageSessionTracker，这里只跑腿 ──

    /** 执行状态机返回的动作序列（顺序保证：CloseSession 先于 ArmTimer 时按序执行）。 */
    private fun handleSessionActions(actions: List<UsageSessionTracker.Action>) {
        actions.forEach { action ->
            when (action) {
                is UsageSessionTracker.Action.ArmTimer -> armSessionTimer(action.minutes)
                UsageSessionTracker.Action.CancelTimer -> sessionJob?.cancel()
                is UsageSessionTracker.Action.CloseSession -> {
                    val r = action.record
                    appScope.launch {
                        usageSessionRepository.record(
                            UsageSession(
                                packageName = r.packageName,
                                startedAt = r.startedAt,
                                endedAt = r.endedAt,
                                durationMillis = r.durationMillis,
                                guardShownCount = r.guardShownCount,
                                endReason = r.endReason,
                            )
                        )
                    }
                }
            }
        }
    }

    /** 装回弹计时：真实墙钟 delay（无事件依赖——应用内停留期间无窗口事件是常态）。 */
    private fun armSessionTimer(minutes: Int) {
        sessionJob?.cancel()
        sessionJob = appScope.launch {
            delay(minutes * UsageSessionTracker.MILLIS_PER_MINUTE)
            onSessionTimerFired()
        }
    }

    private fun onSessionTimerFired() {
        // 到点终验前台（竞速次序兜底）：已切走/不再守护由状态机静默吞掉
        val fired = sessionTracker.onTimerFired(currentForegroundPkg) ?: return
        showGuardOverlay(fired)
    }

    private fun showGuardOverlay(fired: UsageSessionTracker.TimerFired) {
        if (currentOverlay != null || guardOverlay != null) return // 防御性互斥（与拦截层不并存）
        val appLabel = PackageUtil.label(packageManager, fired.packageName)
        val todos = todoRepository.todayTodos(DateUtil.todayString(timeProvider.now()))
        val overlay = UsageGuardOverlay(
            context = this,
            appLabel = appLabel,
            todos = todos,
            elapsedMinutes = fired.elapsedMinutes.toInt(),
            continueMinutes = controller.sessionGuardMinutesGlobal(),
            onChoice = { continued -> onGuardChoice(fired.packageName, continued) },
            onUnavailable = {
                guardOverlay = null
                handleSessionActions(sessionTracker.onGuardUnavailable()) // 本场停守护，不闪退不重试
            },
        )
        guardOverlay = overlay
        overlay.show()
    }

    /** 回弹层选择：继续=撤层重装计时；结束=HOME→250ms 撤层（反闪现契约）+ 清放行标记 + 落会话。 */
    private fun onGuardChoice(pkg: String, continued: Boolean) {
        val overlay = guardOverlay
        guardOverlay = null
        if (continued) {
            overlay?.dismiss() // 应用一直在层后，撤掉即见
            handleSessionActions(sessionTracker.onGuardContinue(controller.sessionGuardMinutes(pkg)))
        } else {
            // 清放行：再次进入走完整 5 秒减速带（与切目标清标记同一写法）
            userOpenedPkg = null
            if (suppressedPkg == pkg) suppressedPkg = null
            performGlobalAction(GLOBAL_ACTION_HOME)
            overlay?.dismiss(delayMs = 250)
            handleSessionActions(sessionTracker.onGuardEnded())
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        currentOverlay?.dismiss()
        guardOverlay?.dismiss()
        handleSessionActions(sessionTracker.onServiceStopped()) // 会话兜底落库（尽力而为）
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        currentOverlay?.dismiss()
        guardOverlay?.dismiss()
        handleSessionActions(sessionTracker.onServiceStopped())
        super.onDestroy()
    }

    override fun onInterrupt() = Unit
}
