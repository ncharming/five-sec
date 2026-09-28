package com.fivesec.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fivesec.app.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒闹钟封装（specs/010；修复轮三改用 setAlarmClock）：全局**单一**「下一响」闹钟（不做按条目
 * 各排一个——同刻聚合是拍板口径，单闹钟模型天然实现且重排幂等）。
 *
 * 为什么用 setAlarmClock 而非 setExactAndAllowWhileIdle（真机复现：亮屏准点、息屏不触发）：
 * ① 它是 AOSP 最高优先级闹钟（PRIORITY_ALARM_CLOCK），触发时系统**真正退出 Doze**
 *    （DeviceIdleController.onAlarmClockSend），而 setExactAndAllowWhileIdle 只「允许在 Doze 内
 *    投递」、不退出；② **完全不需要精确闹钟权限**（SCHEDULE/USE_EXACT_ALARM 权限体系不适用于
 *    它），不存在授权异常静默降级非精确被推迟的路径；③ 状态栏常驻「即将闹钟」图标，且 OEM
 *    省电策略对「用户设定的闹钟」类闹钟最宽容。这正是市面闹钟应用的通行实现。
 *
 * Manifest 仍声明 USE_EXACT_ALARM：仅供 12+ 「精确闹钟触发 → 后台启动前台服务」豁免的兼容面
 * 与 ROM 启发式，调度本身不依赖它、也永不降级。intent 携带触发时刻 extra——receiver 按
 * **计划分钟**反查条目，晚到也不错过当分钟的聚合口径。
 */
@Singleton
class TodoReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** 排下一响；幂等（同一 PendingIntent 覆盖旧闹钟）。 */
    fun scheduleNext(triggerAtMillis: Long) {
        val operation = pendingIntent(triggerAtMillis)
        // showIntent：点状态栏「即将闹钟」图标回到五秒主页（系统闹钟图标的标准语义）
        val showIntent = PendingIntent.getActivity(
            context,
            REQUEST_SHOW,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager?.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), operation)
    }

    /** 取消闹钟（无排程/全清路径）；幂等。extras 不参与 PendingIntent 匹配，任意 extra 均可取消。 */
    fun cancel() {
        alarmManager?.cancel(pendingIntent(0L))
    }

    private fun pendingIntent(triggerAtMillis: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, TodoReminderReceiver::class.java)
                .putExtra(TodoReminderReceiver.EXTRA_TRIGGER_AT, triggerAtMillis),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        /** 全局唯一闹钟的 requestCode（单闹钟模型：始终覆盖同一 PendingIntent）。 */
        private const val REQUEST_CODE = 10_010

        /** 状态栏闹钟图标的 showIntent requestCode。 */
        private const val REQUEST_SHOW = 10_015
    }
}
