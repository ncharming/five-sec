package com.fivesec.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.RingtoneManager
import com.fivesec.app.R

/**
 * 提醒通知渠道（specs/010）：载体渠道**静音**高重要级——铃声与震动的唯一来源是
 * ReminderAlarmService 的 MediaPlayer 循环 + 波形震动（响铃不依赖页面拉起）；若渠道再挂
 * 铃声会「渠道一声 + 服务循环」双响。静默收底通知与载体渠道分离的原因不变：响与不响
 * 无法在同渠道共存。渠道在 FiveSecApp 启动时创建（设置 thereafter 不可变——这正是
 * v1 渠道带铃声、升级后必须换 id 才能摘掉的原因：老渠道 id 在存量设备上启动时删除）。
 *
 * 修复轮四新增「兜底响铃渠道」：API 26+ 通知的声音/震动由**渠道**决定，builder 级
 * setSound/setVibrate 恒被渠道覆盖——前台服务两层启动都被拒时的「一次性响铃通知」
 * 必须挂在自带 ALARM 声+震动的渠道上才真正可闻（minSdk 26 起 builder 挂铃声从未生效）。
 */
object ReminderChannels {

    /** 提醒载体（前台通知 + FSI）：高重要级、无渠道声、无渠道震动（服务负责感官）。 */
    const val CHANNEL_ALARM = "todo_reminder_alarm_v2"

    /**
     * 降级一次性响铃（修复轮四）：前台服务缺席时的最小可闻兜底——渠道级 ALARM 声 +
     * 闹钟式震动波形。与载体渠道分离正是「响与不响无法在同渠道共存」的直接推论。
     */
    const val CHANNEL_ALARM_FALLBACK = "todo_reminder_alarm_fallback"

    /** 60s 超时收底通知：低重要级静默，点开回提醒页仍可完成。 */
    const val CHANNEL_NOTICE = "todo_reminder_notice"

    /** v1 渠道 id（带渠道铃声，已废弃）：启动时删除，防存量设备双响。 */
    private const val CHANNEL_ALARM_V1_DEPRECATED = "todo_reminder_alarm"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val alarmChannel = NotificationChannel(
            CHANNEL_ALARM,
            context.getString(R.string.todo_reminder_channel_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.todo_reminder_channel_alarm_desc)
            setSound(null, null) // 显式置空：个别 ROM 会给高分渠道塞默认声，必须钉死
            enableVibration(false)
        }
        val fallbackChannel = NotificationChannel(
            CHANNEL_ALARM_FALLBACK,
            context.getString(R.string.todo_reminder_channel_fallback),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.todo_reminder_channel_fallback_desc)
            // 可闻性钉在渠道上（API26+ 渠道覆盖 builder）：默认闹钟铃声走 ALARM 音量流；
            // 个别 ROM 默认闹钟 URI 缺失时 setSound(null) 退化为只震（与 Ringer 同性格：宁可少一感）
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            setVibrationPattern(ReminderRinger.VIBRATION_PATTERN)
        }
        val noticeChannel = NotificationChannel(
            CHANNEL_NOTICE,
            context.getString(R.string.todo_reminder_channel_notice),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.todo_reminder_channel_notice_desc)
        }
        manager.deleteNotificationChannel(CHANNEL_ALARM_V1_DEPRECATED)
        manager.createNotificationChannel(alarmChannel)
        manager.createNotificationChannel(fallbackChannel)
        manager.createNotificationChannel(noticeChannel)
    }
}
