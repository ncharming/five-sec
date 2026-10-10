package com.fivesec.app.blocking

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.fivesec.app.R
import com.fivesec.app.domain.model.Exercise
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.TodayTodosSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 拦截覆盖层：由无障碍服务命中目标后，经 WindowManager 绘制全屏
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]。
 * 非 Activity → 不受 OEM（如 ColorOS）"后台 startActivity"静默拦截；复用 [BlockingViewModel] 的 5 秒减速带状态机。
 *
 * 提示语链路已退役（specs/009-retire-hints，经用户拍板"方案A"整体移除）："今日待办"紧凑卡片是覆盖层
 * **唯一**缓冲内容——[todos] 快照（[TodayTodosSnapshot]）注入瞬间定格，四态渲染见
 * specs/009 contracts/todo-card-overlay.md §B：部分完成（「今日待办 D/T」+ ○ 未完成条目，最多 3 行，
 * 超出折叠）/ 全部完成（✓ 整行）/ 无启用条目（引导添加）/ 有启用但今日不轮到（告知）。
 * 条目文本经 [TodoCardText] 投影：取首个非空行截 12 字（多行待办口径，2026-09 修订）。
 * 卡片常驻：空态不再整块隐藏（009 空态二分）。只读、不参与 render() 锁定。
 * 布局优化（用户拍板）：72sp 大倒计时块已删，倒计时数字融进「请先思考 N 秒」行（22sp 品牌绿），
 * 解锁后该行变「✓ 请选择」。条目顺序由 TodoRepository.todayTodos 预排（仅今天→每N天→每周几→每天、
 * 同类型创建日倒序），本层只取头部 3 行——排序与空态判定都不在视图层。
 *
 * 配色取自 res/values/colors.xml 的 brand_* token，与 Compose Color.kt 同源，保证品牌一致。
 * 始终浅色：覆盖层弹出在第三方 app 之上，非本 app 主题上下文。
 *
 * 取消成功态（specs/011 拦截反馈三件套）：选「取消」后倒计时行变「✓ 已抵制」、新增计数副行
 * 「今日第 N 次抵制」+ 60ms 单次震动，展示约 0.8s 才进终态——正反馈发生在**选择之后**，
 * 不触碰「待办卡唯一缓冲内容」契约（009）。[resistCountProvider] 由服务注入（仓库内存镜像），
 * 求值时取当天日期，跨零点的极端场景序号也归零正确。
 */
class BlockingOverlay(
    context: Context,
    appLabel: String,
    todos: TodayTodosSnapshot,
    resistCountProvider: () -> Int = { 1 },
    private val onFinished: (InterceptionOutcome) -> Unit,
) {
    private val ctx: Context = context
    private val viewModel = BlockingViewModel(appLabel, resistCountProvider)
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var added = false
    @Volatile private var finished = false

    // 品牌色（与 Compose 主题同源）
    private val primaryColor = ContextCompat.getColor(ctx, R.color.brand_primary)
    private val onPrimaryColor = ContextCompat.getColor(ctx, R.color.brand_on_primary)
    private val onSurfaceColor = ContextCompat.getColor(ctx, R.color.brand_on_surface)
    private val onSurfaceVariantColor = ContextCompat.getColor(ctx, R.color.brand_on_surface_variant)
    private val surfaceColor = ContextCompat.getColor(ctx, R.color.brand_surface)
    private val disabledContainer = onSurfaceAlpha(0.12f) // M3 禁用容器：onSurface @ 12%
    private val disabledText = onSurfaceAlpha(0.38f)      // M3 禁用文字：onSurface @ 38%

    private fun onSurfaceAlpha(alpha: Float): Int {
        val r = (onSurfaceColor shr 16) and 0xFF
        val g = (onSurfaceColor shr 8) and 0xFF
        val b = onSurfaceColor and 0xFF
        return Color.argb((255 * alpha).toInt(), r, g, b)
    }

    private val titleText = TextView(ctx).apply {
        text = ctx.getString(R.string.blocking_title, viewModel.appLabel)
        setTextColor(onSurfaceColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f) // 对齐 Compose headlineMedium
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    // ── 今日待办紧凑卡片（specs/005→012）：共享组件 OverlayTodoCard——四态渲染口径
    //    全应用一份（012 起回弹层同源），本层只负责挂载与构造时定格快照 ──
    private val todoCard = OverlayTodoCard(ctx, onSurfaceColor, onSurfaceVariantColor, primaryColor)

    // ── 倒计时行（布局优化）：原 72sp 大数字块已删（腾 ~144dp 给待办卡），倒计时数字
    //    融进本行升格为主视觉——「请先思考 N 秒」（22sp 加粗品牌绿）；解锁后变「✓ 请选择」
    //    （✓ 语义从原大数字位迁移），按钮同步变绿表达可选。
    private val countdownLine = TextView(ctx).apply {
        setTextColor(primaryColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    // 成功态计数副行（specs/011）：「今日第 N 次抵制」，仅 Resisted/Finished(CANCELED) 可见
    private val resistCountLine = TextView(ctx).apply {
        setTextColor(onSurfaceVariantColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    // 打开按钮：14dp 圆角实心（渲染时按解锁态在品牌绿/禁用灰间切换）
    private val openBtnBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
    }
    private val cancelBtn = Button(ctx).apply {
        text = ctx.getString(R.string.blocking_cancel)
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(disabledText) // 初始锁定态；render() 按 state 切换
        styleAsTextAction()
    }
    private val openBtn = Button(ctx).apply {
        text = ctx.getString(R.string.blocking_open)
        background = openBtnBg
        setTextColor(disabledText)
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
    }

    private val spacerBeforeTodos = spacer(dp(12)) // 待办卡片前导 spacer（009 起常驻：卡片不再隐藏，四态间距恒定）

    private val root: View = buildRoot()

    init {
        todoCard.render(todos)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** 文字动作按钮样式：透明底、加粗、去大写、去系统最小尺寸（取消按钮共用）。 */
    private fun Button.styleAsTextAction() {
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
        minimumWidth = 0
        minimumHeight = 0
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    private fun spacer(h: Int): View =
        View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h) }

    private fun buildRoot(): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(cancelBtn, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) })
            addView(openBtn, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
        }
        cancelBtn.setOnClickListener { viewModel.cancel() }
        openBtn.setOnClickListener { viewModel.open() }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(titleText)
            addView(spacerBeforeTodos) // 待办卡片前导 spacer（常驻）：标题与待办卡之间 12dp，四态恒定
            addView(todoCard.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(spacer(dp(28)))
            addView(countdownLine)
            addView(resistCountLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(spacer(dp(28)))
            addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        return FrameLayout(ctx).apply {
            setBackgroundColor(surfaceColor)
            addView(
                column,
                FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER
                },
            )
        }
    }

    fun show() {
        if (added) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager.addView(root, params)
            added = true
        } catch (_: Throwable) {
            finish(InterceptionOutcome.INTERRUPTED) // 上不去就按打断处理，让服务清理 currentOverlay
            return
        }
        scope.launch { viewModel.ui.collect { render(it) } }
    }

    private fun render(state: BlockingViewModel.UiState) {
        // 成功态（specs/011）：Resisted 及其后的 Finished(CANCELED) 都保持成功文案——
        // 终态后服务还要 HOME→250ms 撤层，闪回「✓ 请选择」会破坏奖励瞬间
        val resisted = state is BlockingViewModel.UiState.Resisted ||
            (state is BlockingViewModel.UiState.Finished && state.outcome == InterceptionOutcome.CANCELED)
        // 按钮可选项式：仅解锁态可选；Finished(CANCELED) 与成功态同灰——撤层窗口内不闪回绿色
        val unlocked = state is BlockingViewModel.UiState.ChoiceUnlocked ||
            (state is BlockingViewModel.UiState.Finished && state.outcome != InterceptionOutcome.CANCELED)
        // 倒计时融进行内文案：解锁前「请先思考 N 秒」（0 收敛为 1，避免闪现「0 秒」）；
        // 解锁后「✓ 请选择」——大数字位的 ✓ 语义迁到本行；选取消后「✓ 已抵制」（011 成功态）
        countdownLine.text = when {
            resisted -> ctx.getString(R.string.blocking_resisted)
            unlocked -> ctx.getString(R.string.blocking_choose)
            state is BlockingViewModel.UiState.CountingDown ->
                ctx.getString(R.string.blocking_wait, state.remaining.coerceAtLeast(1))
            else -> ctx.getString(R.string.blocking_wait, Exercise.DURATION_SECONDS)
        }
        if (state is BlockingViewModel.UiState.Resisted) {
            resistCountLine.text = ctx.getString(R.string.blocking_resisted_count, state.count)
            resistCountLine.visibility = View.VISIBLE
            OverlayFeedback.vibrateOneShot(ctx, RESIST_VIBRATE_MS) // 成功态一次性 60ms 短震（specs/011，见 OverlayFeedback 类注）
        } else if (!resisted) {
            resistCountLine.visibility = View.GONE
        }

        // 倒计时期间功能禁用；颜色按 M3 规范区分启用/禁用态（替代原先 alpha 写法）
        cancelBtn.isEnabled = unlocked
        openBtn.isEnabled = unlocked
        if (unlocked) {
            openBtnBg.setColor(primaryColor)
            openBtn.setTextColor(onPrimaryColor)
            cancelBtn.setTextColor(primaryColor)
        } else {
            openBtnBg.setColor(disabledContainer)
            openBtn.setTextColor(disabledText)
            cancelBtn.setTextColor(disabledText)
        }

        if (state is BlockingViewModel.UiState.Finished) finish(state.outcome)
    }

    private fun finish(outcome: InterceptionOutcome) {
        if (finished) return
        finished = true
        onFinished(outcome)
    }

    /** 移除覆盖层。delayMs>0 时延迟移除（用于"取消"先回桌面再撤，避免目标闪现）。 */
    fun dismiss(delayMs: Long = 0L) {
        val remove = Runnable {
            try {
                if (added) {
                    windowManager.removeView(root)
                    added = false
                }
            } catch (_: Exception) {
            }
            scope.cancel()
        }
        if (delayMs <= 0L) remove.run() else root.postDelayed(remove, delayMs)
    }

    /** 由服务在生命周期结束（如无障碍被关）时调用。 */
    fun markInterrupted() {
        viewModel.markInterrupted()
    }

    companion object {
        /** 成功态震动时长（specs/011）。 */
        private const val RESIST_VIBRATE_MS = 60L
    }
}
