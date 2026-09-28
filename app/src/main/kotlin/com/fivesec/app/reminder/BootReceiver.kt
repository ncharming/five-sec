package com.fivesec.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 提醒恢复接收器（specs/010 决策 5）：AlarmManager 闹钟在**重启**与**应用覆盖更新**时被系统清空，
 * 用户手改系统时间/时区会平移闹钟语义——四类系统保护广播统一触发全量重排（只排未来，错过即静默）。
 *
 * 强停（force-stop）后闹钟全清且收不到任何广播是系统机制，本接收器无从自愈：已知限制写入 README，
 * 用户重新打开 App 时由 FiveSecApp 启动兜底恢复。exported=false：四个动作均为系统保护广播，
 * 仅系统可发，第三方伪造不可达。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: TodoReminderCoordinator
    @Inject lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        scope.launch {
            try {
                runCatching { coordinator.rescheduleNow() } // 失败只丢一次重排机会，不崩系统广播路径
            } finally {
                pendingResult.finish()
            }
        }
    }
}
