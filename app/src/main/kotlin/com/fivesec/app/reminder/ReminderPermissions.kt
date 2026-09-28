package com.fivesec.app.reminder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * 提醒链路权限状态（specs/010 决策 16；修复轮三以电池优化取代精确闹钟项）：各门限独立查询——
 * 待办页横幅（逐条提示）共用同一实现，口径不漂移。全部只读查询，不做任何申请动作
 * （申请/跳设置由调用方各自负责）。精确闹钟项已删除：调度改 setAlarmClock 后不再依赖
 * 精确闹钟权限体系（见 TodoReminderScheduler KDoc）。
 */
object ReminderPermissions {

    /** 13+ 通知运行时权限缺失（<13 恒 false——通知无需运行时授权）。 */
    fun notificationsMissing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    /**
     * 未加入电池优化白名单（修复轮三新增）：国产 ROM 息屏后的「应用速冻/睡眠待机优化」会冻结
     * 进程，连闹钟广播都收不到（真机复现：亮屏准点、息屏不响）——这是代码层绕不过的系统策略，
     * 市面提醒类应用通行做法就是引导用户忽略电池优化（23+ 恒可查询；个别 ROM 恒已授则不提示）。
     */
    fun batteryMissing(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == false

    /** 14+ 全屏显示未放行（<14 恒 false——FSI 安装即授）。 */
    fun fullScreenMissing(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 &&
            context.getSystemService(android.app.NotificationManager::class.java)
                ?.canUseFullScreenIntent() == false
}
