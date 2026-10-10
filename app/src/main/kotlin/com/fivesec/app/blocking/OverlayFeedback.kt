package com.fivesec.app.blocking

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 覆盖层一次性触感反馈（011 取消成功态 / 012 回弹弹出共用）：「轻拍肩」等级——提醒用户抬起头，
 * 与 specs/010 提醒的闹钟式长震严格区分。无振动器/被系统拒绝时静默降级：触感缺失不阻断任何路径。
 */
object OverlayFeedback {
    fun vibrateOneShot(context: Context, durationMs: Long) {
        try {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }
}
