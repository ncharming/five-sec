package com.fivesec.app.blocking

import android.content.Context
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
import com.fivesec.app.domain.model.TodayTodosSnapshot

/**
 * 使用时长守护回弹覆盖层（specs/012）：连续停留满设定时长后弹出的「二次减速带」——
 * 与拦截覆盖层同语言（[WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] 全屏浅色、
 * 品牌 token、居中、56dp 按钮、共享 [OverlayTodoCard] 待办卡），但性格更轻：**无 5 秒倒计时**
 * （入口减速带强制等待打断的是「进入」的自动性；回弹时用户已在主动使用，回弹是检查点不是闸门），
 * 按钮立即可点，弹出伴随一次 60ms 短震。
 *
 * 选择语义（回调 [onChoice]，选择后本层只负责通知、撤层由服务执行）：
 * 「继续 N 分钟」→ 撤层原地、重装计时；「结束」→ 服务执行 HOME→延迟 250ms 撤层（反闪现契约沿用，
 * 顺序与拦截「取消」路径一致）并清放行标记（再进走完整减速带）。
 * [onUnavailable]：addView 失败（OEM 拦截等）——本场停守护，宁放弃不打扰不闪退；
 * 本层不写任何拦截事件（无减速带发生，interception_events 零触碰）。
 */
class UsageGuardOverlay(
    context: Context,
    appLabel: String,
    todos: TodayTodosSnapshot,
    elapsedMinutes: Int,
    continueMinutes: Int,
    private val onChoice: (continued: Boolean) -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private val ctx: Context = context
    private val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile private var added = false
    @Volatile private var finished = false

    // 品牌色（与拦截覆盖层/Compose 主题同源）；始终浅色（第三方 app 之上，非本 app 主题上下文）
    private val primaryColor = ContextCompat.getColor(ctx, R.color.brand_primary)
    private val onPrimaryColor = ContextCompat.getColor(ctx, R.color.brand_on_primary)
    private val onSurfaceColor = ContextCompat.getColor(ctx, R.color.brand_on_surface)
    private val onSurfaceVariantColor = ContextCompat.getColor(ctx, R.color.brand_on_surface_variant)
    private val surfaceColor = ContextCompat.getColor(ctx, R.color.brand_surface)

    private val titleText = TextView(ctx).apply {
        text = ctx.getString(R.string.guard_title, appLabel, elapsedMinutes)
        setTextColor(onSurfaceColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f) // 对齐 Compose headlineMedium，与拦截标题同层级
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val subtitleText = TextView(ctx).apply {
        text = ctx.getString(R.string.guard_subtitle)
        setTextColor(onSurfaceVariantColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
    }

    // 今日待办卡：与拦截覆盖层同源同四态（specs/009 契约），构造时定格
    private val todoCard = OverlayTodoCard(ctx, onSurfaceColor, onSurfaceVariantColor, primaryColor)

    // 「结束」文字动作按钮（与拦截「取消」同款轻量样式）；「继续」14dp 圆角实心（同「打开」）
    private val endBtn = Button(ctx).apply {
        text = ctx.getString(R.string.guard_end)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setTextColor(primaryColor)
        styleAsTextAction()
    }
    private val continueBtnBg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(primaryColor)
    }
    private val continueBtn = Button(ctx).apply {
        text = ctx.getString(R.string.guard_continue, continueMinutes)
        background = continueBtnBg
        setTextColor(onPrimaryColor)
        isAllCaps = false
        typeface = Typeface.DEFAULT_BOLD
    }

    private val root: View = buildRoot()

    init {
        todoCard.render(todos)
        endBtn.setOnClickListener { choice(continued = false) }
        continueBtn.setOnClickListener { choice(continued = true) }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    /** 文字动作按钮样式：透明底、加粗、去大写、去系统最小尺寸（与拦截「取消」按钮共用语言）。 */
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
            addView(endBtn, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginEnd = dp(8) })
            addView(continueBtn, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(titleText)
            addView(subtitleText, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(spacer(dp(12)))
            addView(todoCard.view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
            onUnavailable() // 上不去：本场停守护（不闪退不重试），让服务清 guardOverlay 槽位
            return
        }
        OverlayFeedback.vibrateOneShot(ctx, GUARD_VIBRATE_MS)
    }

    /** 单次选择守卫：回调一次后本层静默（双击/竞速不重复触发服务路径）。 */
    private fun choice(continued: Boolean) {
        if (finished) return
        finished = true
        onChoice(continued)
    }

    /** 移除覆盖层。delayMs>0 时延迟移除（「结束」先回桌面再撤，避免目标闪现——契约与拦截一致）。 */
    fun dismiss(delayMs: Long = 0L) {
        val remove = Runnable {
            try {
                if (added) {
                    windowManager.removeView(root)
                    added = false
                }
            } catch (_: Exception) {
            }
        }
        if (delayMs <= 0L) remove.run() else root.postDelayed(remove, delayMs)
    }

    companion object {
        /** 回弹弹出短震时长（specs/012）：「轻拍肩」，与成功态同级。 */
        private const val GUARD_VIBRATE_MS = 60L
    }
}
