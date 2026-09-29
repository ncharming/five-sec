package com.fivesec.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fivesec.app.data.repository.TodoRepository
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 到点触发接收器（specs/010）：单一「下一响」闹钟的落点。
 *
 * 流程：按**排程时刻**（EXTRA_TRIGGER_AT，非当前时间——非精确降级晚到数分钟仍按计划分钟聚合）
 * 换算本地 reminderDay + minute → 响前查库过滤（isDueForMinute：启用/轮到/未完成三查，
 * 排程后到点前的勾选/停用/删除全部被最新库态吸收）→ 非空则**启动响铃前台服务**
 * （ReminderAlarmService——铃声震动不依赖页面拉起，息屏/亮屏/FSI 被拒都即时响；
 * 通知由服务作前台通知发出）→ 无论响没响都重排下一响。
 *
 * 降级路径（修复轮四）：12+ 无精确闹钟授权等场景 startForegroundService 同步被拒时，
 * 改发「一次性响铃通知」——挂**兜底响铃渠道**（渠道级 ALARM 声+震动波形；API 26+
 * 渠道覆盖 builder，可闻性必须钉在渠道上），服务缺席时仍有一声一震可闻。
 *
 * goAsync + 注入的应用级 scope：挂起查库期间 receiver 不被系统提前掐掉，finally 里 finish()。
 */
@AndroidEntryPoint
class TodoReminderReceiver : BroadcastReceiver() {

    @Inject lateinit var todoRepository: TodoRepository
    @Inject lateinit var coordinator: TodoReminderCoordinator
    @Inject lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val triggerAtMillis = intent.getLongExtra(EXTRA_TRIGGER_AT, -1L)
        val pendingResult = goAsync()
        scope.launch {
            try {
                runCatching { fire(context, triggerAtMillis) }
                coordinator.rescheduleNow() // 链式永续：无论本场是否响，下一响已排上
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun fire(context: Context, triggerAtMillis: Long) {
        if (triggerAtMillis <= 0) return // 防御：无 extra 的脏触发只负责触发重排
        val zoned = Instant.ofEpochMilli(triggerAtMillis).atZone(ZoneId.systemDefault())
        val reminderDay = zoned.toLocalDate().toString()
        // Locale.ROOT：保证任何系统语言下都产出 ASCII 数字，与库中 HH:mm 严格可比较
        val minute = String.format(java.util.Locale.ROOT, "%02d:%02d", zoned.hour, zoned.minute)

        val due = todoRepository.allTodosOnce().filter {
            TodoReminderPlanner.isDueForMinute(it, reminderDay, minute)
        }
        if (due.isEmpty()) return // 到点前刚勾完/停用/删除：静默（拍板口径——响前查库）

        val ids = due.map { it.id }
        val started = runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ReminderAlarmService::class.java)
                    .setAction(ReminderAlarmService.ACTION_START)
                    .putExtra(ReminderAlarmService.EXTRA_NOTIFICATION, ReminderNotifications.buildAlarmNotification(context, due))
                    .putExtra(ReminderAlarmService.EXTRA_TODO_IDS, ids.toLongArray())
                    .putExtra(ReminderAlarmService.EXTRA_DAY, reminderDay)
                    .putExtra(ReminderAlarmService.EXTRA_MINUTE, minute),
            )
        }
        // 服务起不来（12+ 无精确闹钟授权等）：一次性响铃通知兜底——渠道一声一震 + 点开进页
        if (started.isFailure) {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, ReminderNotifications.buildAlarmNotification(context, due, degraded = true))
        }
    }

    companion object {
        const val EXTRA_TRIGGER_AT = "trigger_at"

        /** 提醒通知固定 id（服务前台通知同 id）：同时最多一场提醒在处理，重发覆盖而非堆叠。 */
        const val NOTIFICATION_ID = 10_012
    }
}
