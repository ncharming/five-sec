package com.fivesec.app.blocking

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.fivesec.app.R
import com.fivesec.app.domain.model.TodayTodosSnapshot

/**
 * 覆盖层今日待办紧凑卡片（specs/005 起源、009 定型四态、012 从 BlockingOverlay 提取共享）：
 * 拦截覆盖层与使用时长回弹层（012）同源渲染——四态口径全应用只有这一份，不随新增覆盖层复制。
 *
 * 内容在构造后 [render] 一次定格（快照注入，无后续刷新）；空态常驻不整块隐藏（009：待办是
 * 覆盖层唯一缓冲内容）。卡片居中（2026-09 用户拍板：与覆盖层整体居中语言统一）。
 * 条目文本经 [TodoCardText] 投影（取首个非空行截 12 字）；排序与空态判定不在视图层。
 */
class OverlayTodoCard(
    context: Context,
    private val onSurfaceColor: Int,
    private val onSurfaceVariantColor: Int,
    private val primaryColor: Int,
) {
    private val ctx: Context = context

    private val todoTitle = TextView(ctx).apply {
        setTextColor(onSurfaceColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val todoItems = TextView(ctx).apply {
        setTextColor(onSurfaceVariantColor)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setLineSpacing(dp(4).toFloat(), 1f)
    }

    /** 卡片根视图（垂直 LinearLayout），调用方以自身布局参数挂载。 */
    val view: LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(todoTitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(todoItems, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    /** 待办卡片内容填充（specs/009 contracts/todo-card-overlay.md §B 四态渲染表）：
     *  卡片常驻——空态不再整块隐藏；空态二分由快照 anyEnabled 驱动，判定不进视图层。 */
    fun render(todos: TodayTodosSnapshot) {
        val items = todos.items
        if (items.isEmpty()) {
            todoTitle.text = if (todos.anyEnabled) {
                ctx.getString(R.string.blocking_todos_empty_none_due) // 有启用条目但今日无一轮到
            } else {
                ctx.getString(R.string.blocking_todos_empty_none) // 一条启用的都没有：引导去添加
            }
            todoTitle.setTextColor(onSurfaceVariantColor)
            todoItems.visibility = View.GONE
            return
        }
        val done = items.count { it.isDone }
        val pending = items.filterNot { it.isDone }
        if (pending.isEmpty()) {
            todoTitle.text = ctx.getString(R.string.blocking_todos_all_done)
            todoTitle.setTextColor(primaryColor)
            todoItems.visibility = View.GONE
            return
        }
        todoTitle.text = ctx.getString(R.string.blocking_todos_title, done, items.size)
        todoTitle.setTextColor(onSurfaceColor)
        todoItems.visibility = View.VISIBLE
        val shown = pending.take(TODO_MAX_LINES)
        val text = shown.joinToString("\n") { TODO_BULLET + TodoCardText.project(it.text) }
        val overflow = pending.size - shown.size
        todoItems.text = if (overflow > 0) {
            text + "\n" + ctx.getString(R.string.blocking_todos_more, overflow)
        } else {
            text
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    companion object {
        /** 待办条目最多展示行数（用户拍板：排序后只展示前 3 条——一次性/间隔类优先露出，
         *  剩余折叠进 blocking_todos_more），超出折叠。 */
        const val TODO_MAX_LINES = 3

        /** 未完成条目前缀符号（与 "✓" 同属覆盖层符号常量，不入资源）。 */
        const val TODO_BULLET = "○ "
    }
}
