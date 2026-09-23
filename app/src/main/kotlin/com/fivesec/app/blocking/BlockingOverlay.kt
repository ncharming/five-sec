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
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.domain.model.TodayTodo
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
 * 提示语由服务侧经 HintRepository 循环游标决定（specs/005-daily-todos：内置+池单一序列轮转），经 [hint] 注入。
 * "今日待办"紧凑卡片（specs/005-daily-todos）在 [todos] 注入瞬间定格：标题「今日待办 D/T」+ 未完成条目
 * （○ 前缀，最多 3 行，超出折叠）；全部完成显示完成态整行；启用数为 0 整块隐藏。只读、不参与 render() 锁定。
 * 004 的"拦截页写提示语"输入行已整体移除（specs/005：栈式机制退役，池在提示语页维护）。
 *
 * 配色取自 res/values/colors.xml 的 brand_* token，与 Compose Color.kt 同源，保证品牌一致。
 * 始终浅色：覆盖层弹出在第三方 app 之上，非本 app 主题上下文。
 */
class BlockingOverlay(
    context: Context,
    appLabel: String,
    hint: String,
    todos: List<TodayTodo>,
    private val onFinished: (InterceptionOutcome) -> Unit,
) {
    private val ctx: Context = context
    private val viewModel = BlockingViewModel(appLabel)
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
    private val hintText = TextView(ctx).apply {
        text = hint
        setTextColor(onSurfaceVariantColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
    }

    // ── 今日待办紧凑卡片（specs/005-daily-todos）：标题行 + 条目行，内容在构造时一次定格 ──
    // 左对齐（specs/007）：卡片内标题与条目整体靠左（更像一张清单）；卡片外提示语/倒计时/按钮维持居中
    private val todoTitle = TextView(ctx).apply {
        setTextColor(onSurfaceColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.START
    }
    private val todoItems = TextView(ctx).apply {
        setTextColor(onSurfaceVariantColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.START
        setLineSpacing(dp(4).toFloat(), 1f)
    }
    private val todoBlock = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(todoTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(todoItems, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    private val countdownText = TextView(ctx).apply {
        setTextColor(primaryColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val waitText = TextView(ctx).apply {
        setTextColor(onSurfaceVariantColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
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

    private val spacerBeforeTodos = spacer(dp(12)) // 待办卡片前导 spacer：空清单时与卡片一起 GONE，布局回现状

    private val root: View = buildRoot()

    init {
        applyTodos(todos)
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

    /** 待办卡片内容填充（contracts/todo-ui.md 渲染规则表）：空清单整块 GONE（含前导 spacer），全完成仅标题行。 */
    private fun applyTodos(todos: List<TodayTodo>) {
        if (todos.isEmpty()) {
            todoBlock.visibility = View.GONE
            spacerBeforeTodos.visibility = View.GONE
            return
        }
        spacerBeforeTodos.visibility = View.VISIBLE
        todoBlock.visibility = View.VISIBLE
        val done = todos.count { it.isDone }
        val pending = todos.filterNot { it.isDone }
        if (pending.isEmpty()) {
            todoTitle.text = ctx.getString(R.string.blocking_todos_all_done)
            todoTitle.setTextColor(primaryColor)
            todoItems.visibility = View.GONE
            return
        }
        todoTitle.text = ctx.getString(R.string.blocking_todos_title, done, todos.size)
        todoTitle.setTextColor(onSurfaceColor)
        todoItems.visibility = View.VISIBLE
        val shown = pending.take(TODO_MAX_LINES)
        val text = shown.joinToString("\n") { TODO_BULLET + truncateForCard(it.text) }
        val overflow = pending.size - shown.size
        todoItems.text = if (overflow > 0) {
            text + "\n" + ctx.getString(R.string.blocking_todos_more, overflow)
        } else {
            text
        }
    }

    /** 卡片单条展示截断（specs/007 收紧为 12 字）：存储可到 200 字（待办页看全文），卡片是 5 秒
     *  阅读场景，只留前 12 字（超长加省略号，省略号不计入 12）。 */
    private fun truncateForCard(text: String): String =
        if (text.length > TODO_DISPLAY_MAX) text.take(TODO_DISPLAY_MAX) + "…" else text

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
            addView(spacer(dp(12)))
            addView(hintText)
            addView(spacerBeforeTodos) // 待办卡片前导 spacer（类字段）：空清单时与卡片一起 GONE，布局回现状
            addView(todoBlock, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(spacer(dp(24)))
            addView(
                countdownText,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96)).apply { gravity = Gravity.CENTER },
            )
            addView(spacer(dp(24)))
            addView(waitText)
            addView(spacer(dp(24)))
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
        val unlocked = state is BlockingViewModel.UiState.ChoiceUnlocked ||
            state is BlockingViewModel.UiState.Finished
        countdownText.text = when {
            unlocked -> "✓"
            state is BlockingViewModel.UiState.CountingDown -> state.remaining.coerceAtLeast(0).toString()
            else -> "5"
        }
        waitText.text = if (unlocked) "" else ctx.getString(R.string.blocking_wait)

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
        /** 待办条目最多展示行数：5 秒内可读的上限，超出折叠进 blocking_todos_more。 */
        private const val TODO_MAX_LINES = 3

        /** 卡片单条展示字符上限（specs/007：30→12，5 秒可读更克制）：完整内容在待办页看，超长加省略号。 */
        private const val TODO_DISPLAY_MAX = 12

        /** 未完成条目前缀符号（与 "✓" 同属覆盖层符号常量，不入资源）。 */
        private const val TODO_BULLET = "○ "
    }
}
