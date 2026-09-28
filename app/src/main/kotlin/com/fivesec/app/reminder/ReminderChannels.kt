package com.fivesec.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager

/**
 * 提醒通知渠道（specs/010）：两渠道分离的根因——FSI 载体渠道必须挂闹钟铃声（14+ 未放行全屏时
 * 降级为高分横幅仍能响数秒），而 60s 超时收底通知必须静默；同一个渠道无法同时满足「响」与「不响」。
 * 渠道在 FiveSecApp 启动时创建（渠道设置 thereafter 不可变，Android 通知模型约束）。
 */
object ReminderChannels {

    /** FSI 全屏提醒载体：高重要级 + 系统默认闹钟铃声（ALARM 属性）+ 渠道震动。 */
    const val CHANNEL_ALARM = "todo_reminder_alarm"

    /** 60s 超时收底通知：低重要级静默，点开回提醒页仍可完成。 */
    const val CHANNEL_NOTICE = "todo_reminder_notice"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(com.fivesec.app.R.string.todo_reminder_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(com.fivesec.app.R.string.todo_reminder_channel_alarm_desc)
            setSound(
                alarmSound,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
        }
        val noticeChannel = NotificationChannel(
            CHANNEL_NOTICE,
            context.getString(com.fivesec.app.R.string.todo_reminder_channel_notice),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(com.fivesec.app.R.string.todo_reminder_channel_notice_desc)
        }
        manager.createNotificationChannel(alarmChannel)
        manager.createNotificationChannel(noticeChannel)
    }
}
