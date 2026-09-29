package com.fivesec.app.reminder

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.fivesec.app.R
import com.fivesec.app.domain.model.Todo

/**
 * 提醒通知装配（specs/010 修复轮四）：Receiver 主路径与服务降级路径共用的唯一装配点。
 *
 * 为什么从 Receiver 抽出：服务侧 [ReminderAlarmService.startForeground] 被拒（12+/OEM
 * 的第二道闸）时同样要发「一次性响铃通知」，两处共用一份装配防预览/FSI/ requestCode
 * 口径漂移。
 *
 * 降级可闻性的关键（修复轮四根因）：API 26+ 通知的声音/震动由**渠道**决定，builder 的
 * setSound/setVibrate 恒被渠道覆盖——主载体渠道 [ReminderChannels.CHANNEL_ALARM] 按决策 7
 * 钉死静音（铃声唯一来源在服务的 MediaPlayer，防双响），因此降级版必须挂独立的
 * [ReminderChannels.CHANNEL_ALARM_FALLBACK]（渠道级 ALARM 声+震动）才真正可闻；此前
 * 「builder 挂铃声」的写法自 minSdk 26 起从未响过。
 */
internal object ReminderNotifications {

    /**
     * 提醒载体通知：主路径 = 静音载体渠道 + FSI（锁屏/息屏直拉全屏页、亮屏出高分横幅，
     * 服务补拉页面）；降级 = 兜底响铃渠道（服务两层启动都被拒时的最小可闻兜底：渠道一声
     * ALARM 铃 + 震动波形，点开进提醒页）。
     */
    fun buildAlarmNotification(context: Context, due: List<Todo>, degraded: Boolean = false): Notification =
        NotificationCompat.Builder(
            context,
            if (degraded) ReminderChannels.CHANNEL_ALARM_FALLBACK else ReminderChannels.CHANNEL_ALARM,
        )
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

    /** 内容/FSI PendingIntent 的 requestCode（同一目标 Activity，extras 随最新触发更新）。 */
    private const val REQUEST_CONTENT = 10_011

    /** 通知正文预览条数（与覆盖层卡片「前 3 条」同性格：预览克制，全量进提醒页看）。 */
    private const val PREVIEW_COUNT = 3
}
