package com.fivesec.app.reminder

import android.app.Application
import android.app.NotificationManager
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.domain.model.Todo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 提醒通知渠道契约（specs/010 修复轮四）：钉死「哪条通知挂哪个渠道 + 各渠道可闻性」。
 *
 * 根因背景：API 26+ 通知的声音/震动由渠道决定、builder 级 setSound/setVibrate 恒被渠道
 * 覆盖——降级一次性响铃通知若仍挂钉死静音的载体渠道，则自 minSdk 26 起「可闻兜底」
 * 从未真正响过（本测试要抓住的回归形态）。
 */
@RunWith(RobolectricTestRunner::class)
class ReminderNotificationsTest {

    private lateinit var context: Application

    private val due = listOf(
        Todo(id = 1, text = "俯卧撑十个"),
        Todo(id = 2, text = "读十页书"),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ReminderChannels.createChannels(context) // 与 FiveSecApp 启动同口径：渠道先于任何通知存在
    }

    @Test
    fun `主路径通知挂静音载体渠道——铃声唯一来源在前台服务`() {
        val notification = ReminderNotifications.buildAlarmNotification(context, due)
        assertEquals(ReminderChannels.CHANNEL_ALARM, notification.channelId)
    }

    @Test
    fun `降级通知挂兜底响铃渠道——渠道覆盖 builder，可闻性必须钉在渠道上`() {
        val notification = ReminderNotifications.buildAlarmNotification(context, due, degraded = true)
        assertEquals(ReminderChannels.CHANNEL_ALARM_FALLBACK, notification.channelId)
    }

    @Test
    fun `兜底渠道带 ALARM 声音与震动波形——服务缺席时最小可闻兜底成立`() {
        val channel = channel(ReminderChannels.CHANNEL_ALARM_FALLBACK)
        assertNotNull("兜底渠道必须自带铃声（渠道覆盖 builder，builder 挂声无效）", channel.sound)
        assertEquals(AudioAttributes.USAGE_ALARM, channel.audioAttributes.usage)
        assertTrue(channel.shouldVibrate())
        assertArrayEquals(ReminderRinger.VIBRATION_PATTERN, channel.vibrationPattern)
    }

    @Test
    fun `载体渠道钉死静音——防渠道一声与服务循环双响`() {
        val channel = channel(ReminderChannels.CHANNEL_ALARM)
        assertFalse(channel.shouldVibrate())
        assertNull(channel.sound)
    }

    private fun channel(id: String) =
        context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)
}
