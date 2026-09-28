package com.fivesec.app.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
 * 排程后到点前的勾选/停用/删除全部被最新库态吸收）→ 非空则**启动响铃前台服务**
 * （ReminderAlarmService——铃声震动不依赖页面拉起，息屏/亮屏/FSI 被拒都即时响；
 * 通知由服务作前台通知发出）→ 无论响没响都重排下一响。
 *
 * 降级路径：12+ 无精确闹钟授权时闹钟走非精确、后台启动前台服务可能被拒——catch 后改发
 * 「一次性响铃通知」（builder 级铃声+震动：渠道静音、服务缺席时兜底响一声一震）。
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

        val ids = due.map { it.id }
        val started = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReminderAlarmService::class.java)
                    .setAction(ReminderAlarmService.ACTION_START)
                    .putExtra(ReminderAlarmService.EXTRA_NOTIFICATION, buildAlarmNotification(context, due))
                    .putExtra(ReminderAlarmService.EXTRA_TODO_IDS, ids.toLongArray())
                    .putExtra(ReminderAlarmService.EXTRA_DAY, reminderDay)
                    .putExtra(ReminderAlarmService.EXTRA_MINUTE, minute),
            )
        }
        // 服务起不来（12+ 无精确闹钟授权等）：一次性响铃通知兜底——响一声一震 + 点开进页
        if (started.isFailure) {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, buildAlarmNotification(context, due, degraded = true))
        }
    }

    /**
     * 提醒载体通知：静音渠道（铃声单一来源在服务的 MediaPlayer 循环，防渠道一声+循环双响），
     * FSI 锁屏直拉全屏页；降级版在 builder 挂一次性铃声+震动（服务缺席时的最小可闻兜底）。
     */
    private fun buildAlarmNotification(context: Context, due: List<Todo>, degraded: Boolean = false) =
        NotificationCompat.Builder(context, ReminderChannels.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_todo_reminder)
            .setContentTitle(context.getString(R.string.todo_reminder_notification_title))
            .setContentText(previewText(context, due))
            .setStyle(NotificationCompat.BigTextStyle().bigText(previewText(context, due)))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(contentIntent(context, due))
            .setFullScreenIntent(contentIntent(context, due), true) // 锁屏/息屏直接拉全屏页；亮屏出高分横幅（服务补拉页面）
            .setAutoCancel(true)
            .setOngoing(true) // 提醒页处理中不滑掉；页面/服务收口时统一清掉
            .apply {
                if (degraded) {
                    // NotificationCompat 约定：sound 传 usage 常量（框架侧自行装配 ALARM 属性）
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.USAGE_ALARM)
                    setVibrate(ReminderRinger.VIBRATION_PATTERN)
                }
            }
            .build()

    private fun previewText(context: Context, due: List<Todo>): String {
        val titles = due.map { it.text }
        return if (titles.size <= PREVIEW_COUNT) {
            titles.joinToString("；")
        } else {
            titles.take(PREVIEW_COUNT).joinToString("；") +
                context.getString(R.string.todo_reminder_notification_more, titles.size - PREVIEW_COUNT)
        }
    }

    private fun contentIntent(context: Context, due: List<Todo>): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, TodoReminderActivity::class.java)
                .putExtra(TodoReminderActivity.EXTRA_TODO_IDS, due.map { it.id }.toLongArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        const val EXTRA_TRIGGER_AT = "trigger_at"

        /** 提醒通知固定 id（服务前台通知同 id）：同时最多一场提醒在处理，重发覆盖而非堆叠。 */
        const val NOTIFICATION_ID = 10_012

        /** 内容/FSI PendingIntent 的 requestCode（同一目标 Activity，extras 随最新触发更新）。 */
        private const val REQUEST_CONTENT = 10_011

        /** 通知正文预览条数（与覆盖层卡片「前 3 条」同性格：预览克制，全量进提醒页看）。 */
        private const val PREVIEW_COUNT = 3
    }
}
