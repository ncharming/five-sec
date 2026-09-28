package com.fivesec.app.reminder

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 提醒链路权限状态（specs/010 决策 16）：三项门限各自独立查询——待办页横幅（逐条提示）与
 * receiver 发通知前的防御检查共用同一实现，口径不漂移。全部只读查询，不做任何申请动作
 * （申请/跳设置由调用方各自负责）。
 */
object ReminderPermissions {

    /** 13+ 通知运行时权限缺失（<13 恒 false——通知无需运行时授权）。 */
    fun notificationsMissing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * 12+ 精确闹钟不可用（<12 恒 false——无此限制）。修复轮二起 Manifest 已改声明
     * USE_EXACT_ALARM（安装即自动授予、不可撤销），正常恒 false——本查询保留作 ROM 异常
     * 兜底与横幅数据源；一旦真出现缺失即降级非精确闹钟（Doze 下会被推迟到亮屏）。
     */
    fun exactAlarmMissing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 &&
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == false

    /** 14+ 全屏显示未放行（<14 恒 false——FSI 安装即授）。 */
    fun fullScreenMissing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.canUseFullScreenIntent() == false
}
