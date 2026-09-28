package com.fivesec.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 提醒闹钟封装（specs/010）：全局**单一**「下一响」闹钟（不做按条目各排一个——同刻聚合是拍板口径，
 * 单闹钟模型天然实现且重排幂等）。RTC_WAKEUP：到点唤醒设备（类闹钟语义）。
 *
 * 精确策略（决策 4）：canScheduleExactAlarms 真 → setExactAndAllowWhileIdle（分秒不差）；
 * 假 → 降级 setAndAllowWhileIdle（仍响，可能漂移数分钟，待办页横幅提示）。
 * intent 携带触发时刻 extra——receiver 按**计划分钟**反查条目，晚到数分钟也不错过当分钟的聚合。
 */
@Singleton
class TodoReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** 排下一响；幂等（同一 PendingIntent 覆盖旧闹钟）。 */
    fun scheduleNext(triggerAtMillis: Long) {
        val pi = pendingIntent(triggerAtMillis)
        val exact = Build.VERSION.SDK_INT < 31 || alarmManager?.canScheduleExactAlarms() == true
        if (exact) {
            alarmManager?.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        } else {
            alarmManager?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        }
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
    }
}
