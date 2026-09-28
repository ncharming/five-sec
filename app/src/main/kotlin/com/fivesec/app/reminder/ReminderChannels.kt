package com.fivesec.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * 提醒通知渠道（specs/010）：载体渠道**静音**高重要级——铃声与震动的唯一来源是
 * ReminderAlarmService 的 MediaPlayer 循环 + 波形震动（响铃不依赖页面拉起）；若渠道再挂
 * 铃声会「渠道一声 + 服务循环」双响。静默收底通知与载体渠道分离的原因不变：响与不响
 * 无法在同渠道共存。渠道在 FiveSecApp 启动时创建（设置 thereafter 不可变——这正是
 * v1 渠道带铃声、升级后必须换 id 才能摘掉的原因：老渠道 id 在存量设备上启动时删除）。
 */
object ReminderChannels {

    /** 提醒载体（前台通知 + FSI）：高重要级、无渠道声、无渠道震动（服务负责感官）。 */
    const val CHANNEL_ALARM = "todo_reminder_alarm_v2"

    /** 60s 超时收底通知：低重要级静默，点开回提醒页仍可完成。 */
    const val CHANNEL_NOTICE = "todo_reminder_notice"

    /** v1 渠道 id（带渠道铃声，已废弃）：启动时删除，防存量设备双响。 */
    private const val CHANNEL_ALARM_V1_DEPRECATED = "todo_reminder_alarm"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(com.fivesec.app.R.string.todo_reminder_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(com.fivesec.app.R.string.todo_reminder_channel_alarm_desc)
            setSound(null, null) // 显式置空：个别 ROM 会给高分渠道塞默认声，必须钉死
            enableVibration(false)
        }
        val noticeChannel = NotificationChannel(
            CHANNEL_NOTICE,
            context.getString(com.fivesec.app.R.string.todo_reminder_channel_notice),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(com.fivesec.app.R.string.todo_reminder_channel_notice_desc)
        }
        manager.deleteNotificationChannel(CHANNEL_ALARM_V1_DEPRECATED)
        manager.createNotificationChannel(alarmChannel)
        manager.createNotificationChannel(noticeChannel)
    }
}
