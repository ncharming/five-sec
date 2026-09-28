package com.fivesec.app.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fivesec.app.R
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.Todo
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 到点触发接收器（specs/010）：单一「下一响」闹钟的落点。
 *
 * 流程：按**排程时刻**（EXTRA_TRIGGER_AT，非当前时间——非精确降级晚到数分钟仍按计划分钟聚合）
 * 换算本地 reminderDay + minute → 响前查库过滤（isDueForMinute：启用/轮到/未完成三查，
 * 排程后到点前的勾选/停用/删除全部被最新库态吸收）→ 非空则发 full-screen intent 通知拉起全屏
 * 提醒页（13+ 无通知权限则静默跳过——横幅已教育，不做无 UI 的幽灵响铃）→ 无论响没响都重排下一响。
 *
 * goAsync + 注入的应用级 scope：挂起查库期间 receiver 不被系统提前掐掉，finally 里 finish()。
 */
@AndroidEntryPoint
class TodoReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var todoRepository: TodoRepository
    @Inject lateinit var coordinator: TodoReminderCoordinator
    @Inject lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val triggerAtMillis = intent.getLongExtra(EXTRA_TRIGGER_AT, -1L)
        val pendingResult = goAsync()
        scope.launch {
            try {
                runCatching { fire(context, triggerAtMillis) }
                coordinator.rescheduleNow() // 链式永续：无论本场是否响，下一响已排上
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun fire(context: Context, triggerAtMillis: Long) {
        if (triggerAtMillis <= 0) return // 防御：无 extra 的脏触发只负责触发重排
        val zoned = Instant.ofEpochMilli(triggerAtMillis).atZone(ZoneId.systemDefault())
        val reminderDay = zoned.toLocalDate().toString()
        // Locale.ROOT：保证任何系统语言下都产出 ASCII 数字，与库中 HH:mm 严格可比较
        val minute = String.format(java.util.Locale.ROOT, "%02d:%02d", zoned.hour, zoned.minute)

        val due = todoRepository.allTodosOnce().filter {
            TodoReminderPlanner.isDueForMinute(it, reminderDay, minute)
        }
        if (due.isEmpty()) return // 到点前刚勾完/停用/删除：静默（拍板口径——响前查库）
        if (ReminderPermissions.notificationsMissing(context)) return // 13+ 无通知权限：无 UI 可展示

        postFullScreenNotification(context, due)
    }

    private fun postFullScreenNotification(context: Context, due: List<Todo>) {
        val ids = due.map { it.id }.toLongArray()
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, TodoReminderActivity::class.java)
                .putExtra(TodoReminderActivity.EXTRA_TODO_IDS, ids)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val titles = due.map { it.text }
        val text = if (titles.size <= PREVIEW_COUNT) {
            titles.joinToString("；")
        } else {
            titles.take(PREVIEW_COUNT).joinToString("；") +
                context.getString(R.string.todo_reminder_notification_more, titles.size - PREVIEW_COUNT)
        }
        val notification = NotificationCompat.Builder(context, ReminderChannels.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_todo_reminder)
            .setContentTitle(context.getString(R.string.todo_reminder_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentIntent)
            .setFullScreenIntent(contentIntent, true) // 锁屏/后台时直接拉全屏页；14+ 未放行则降级高分横幅
            .setAutoCancel(true)
            .setOngoing(true) // 提醒页处理中不滑掉；页内关闭/超时收底时统一清掉
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_TRIGGER_AT = "trigger_at"

        /** 内容/FSI PendingIntent 的 requestCode（同一目标 Activity，extras 随最新触发更新）。 */
        private const val REQUEST_CONTENT = 10_011

        /** 提醒通知固定 id：同时最多一场提醒在处理，重发覆盖而非堆叠。 */
        private const val NOTIFICATION_ID = 10_012

        /** 通知正文预览条数（与覆盖层卡片「前 3 条」同性格：预览克制，全量进提醒页看）。 */
        private const val PREVIEW_COUNT = 3
    }
}
