package com.fivesec.app.blocking

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.fivesec.app.R
import com.fivesec.app.domain.model.InterceptionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 拦截覆盖层：由无障碍服务命中目标后，经 WindowManager 绘制全屏
 * [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]。
 * 非 Activity → 不受 OEM（如 ColorOS）"后台 startActivity"静默拦截；复用 [BlockingViewModel] 的 5 秒减速带状态机。
 */
class BlockingOverlay(
    context: Context,
    appLabel: String,
    private val onFinished: (InterceptionOutcome) -> Unit,
) {
    private val ctx: Context = context
    private val viewModel = BlockingViewModel(appLabel)
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var added = false
    @Volatile private var finished = false

    private val titleText = TextView(ctx).apply {
        text = ctx.getString(R.string.blocking_title, viewModel.appLabel)
        setTextColor(Color.parseColor("#1C1B1F"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
    }
    private val hintText = TextView(ctx).apply {
        text = ctx.resources.getStringArray(R.array.blocking_exercise_hints).random()
        setTextColor(Color.parseColor("#49454F"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
    }
    private val countdownText = TextView(ctx).apply {
        setTextColor(Color.parseColor("#6750A4"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
        gravity = Gravity.CENTER
    }
    private val waitText = TextView(ctx).apply {
        setTextColor(Color.parseColor("#79747E"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
    }
    private val cancelBtn = Button(ctx).apply {
        text = ctx.getString(R.string.blocking_cancel)
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.parseColor("#49454F"))
    }
    private val openBtn = Button(ctx).apply {
        text = ctx.getString(R.string.blocking_open)
        backgroundTintList = ColorStateList.valueOf(Color.parseColor("#6750A4"))
        setTextColor(Color.WHITE)
    }

    private val root: View = buildRoot()

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

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
            addView(spacer(dp(12)))
            addView(hintText)
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
            setBackgroundColor(Color.WHITE)
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

        // 按钮置灰且禁用状态：倒计时期间视觉上置灰，功能上禁用
        cancelBtn.isEnabled = unlocked
        openBtn.isEnabled = unlocked

        // 视觉置灰处理：使用 alpha 值来模拟置灰效果
        val buttonAlpha = if (unlocked) 1.0f else 0.5f
        cancelBtn.alpha = buttonAlpha
        openBtn.alpha = buttonAlpha

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
}
