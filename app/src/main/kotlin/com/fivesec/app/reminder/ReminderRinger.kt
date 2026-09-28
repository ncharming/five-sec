package com.fivesec.app.reminder

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 提醒响铃器（specs/010 决策 7）：系统默认闹钟铃声 + ALARM 音量流（用户调系统闹钟音量即对五秒生效，
 * 类闹钟心智）+ 闹钟式循环震动波形。零内置音频（纯离线原则：只用系统资源）。
 *
 * 防御：个别 ROM 默认闹钟 URI 缺失或 MediaPlayer 打不开时 runCatching 静默降级——只震不响不崩
 * （宁可少一感不闪退，与 overlay addView 降级同思路）。生命周期由持有方（响铃前台服务）显式管理。
 */
class ReminderRinger(private val context: Context) {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    /** 开始响铃+震动；幂等（先停旧再起新）。 */
    fun start() {
        stop()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        if (uri != null) {
            runCatching {
                player = MediaPlayer().apply {
                    setDataSource(context, uri)
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    isLooping = true
                    prepare()
                    start()
                }
            }
        }
        vibrator = context.getSystemService(Vibrator::class.java)?.also {
            it.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, VIBRATION_REPEAT_FROM_START))
        }
    }

    /** 停止响铃+震动；幂等，可安全重复调用（关闭页/超时/前台切换多路径收口）。 */
    fun stop() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        vibrator?.cancel()
        vibrator = null
    }

    companion object {
        /** 闹钟式波形：震 1s 停 0.5s 循环。internal：降级路径的一次性通知震动同款波形。 */
        internal val VIBRATION_PATTERN = longArrayOf(0, 1_000, 500)

        /** createWaveform 的 repeat 索引：0 = 从头循环。 */
        private const val VIBRATION_REPEAT_FROM_START = 0
    }
}
