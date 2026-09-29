package com.fivesec.app.reminder

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.fivesec.app.R
import com.fivesec.app.data.repository.TodoRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 提醒响铃前台服务（specs/010 修复轮）：铃声与震动的唯一持有方。
 *
 * 为什么从全屏页挪进服务：full-screen intent 只在息屏/锁屏时拉起 Activity，亮屏解锁时平台
 * 规定只出高分横幅（部分 ROM 还默认拒绝 FSI）——原实现把响铃绑在 Activity.onCreate，导致
 * 「只亮横幅、点了才响」。服务由 Receiver 到点直接启动，与页面拉起与否完全解耦：两种屏幕
 * 状态都即时响铃+震动。前台类型 mediaPlayback（正在播放闹钟铃声即媒体播放的本义），
 * 12+ 后台启动前台服务的豁免恰好覆盖「精确闹钟触发」这一路径；被拒时 Receiver 侧退化为
 * 「一次性响铃通知」，铃声最多迟到、不会消失。
 *
 * 停铃三路径不变（先到先赢）：① 页面任意交互（ACTION_STOP_RING——人已到场）；
 * ② 60s 无操作自动收底（响前口径重查剩余条目 → 静默通知 → 自灭）；
 * ③ 页面关闭/全勾完（ACTION_FINISH——经 Activity.finish() 统一收口发送）。
 * 响铃期间持 CPU 唤醒锁（60s+余量）：息屏且 FSI 被拒时屏幕不亮，CPU 睡死会让铃声断续。
 *
 * 降级收口（修复轮四）：前台化被拒（startForeground 才抛的 OEM 变体）时按本场口径重查库
 * 发「兜底响铃通知」（渠道级一声 ALARM 铃+震动）再自灭——两层启动闸（Receiver 的
 * startForegroundService 同步抛、此处的 startForeground 抛）任一被拒都仍可闻。
 */
@AndroidEntryPoint
class ReminderAlarmService : Service() {

    @Inject lateinit var todoRepository: TodoRepository

    private val scope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private var ringer: ReminderRinger? = null
    private var timeoutJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /** 本场提醒的触发口径（超时收底重查用）：非重排场景下天然非空。 */
    private var pendingIds: List<Long> = emptyList()
    private var reminderDay: String = ""
    private var minute: String = ""

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAlarm(intent)

            // 人已到场（页面任意触摸/勾完成）：铃使命完成；服务保留——全勾/关页时 FINISH 统一收口
            ACTION_STOP_RING -> {
                timeoutJob?.cancel()
                stopRing()
            }

            // 提醒已处理完（页面关闭或全勾自动关页）：撤前台通知、停铃、自灭
            ACTION_FINISH -> {
                stopRing()
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                NotificationManagerCompat.from(this).cancel(TodoReminderReceiver.NOTIFICATION_ID)
                stopSelf()
            }

            else -> stopSelf() // 脏启动（如系统重放）：无口径不可响，直接自灭
        }
        return START_NOT_STICKY // 闹钟是一次性的；被杀不复活（下一响由闹钟链路自行触发）
    }

    private fun startAlarm(intent: Intent) {
        // getParcelableExtra 泛型重载 33+ 弃用且无兼容替代（本项目 androidx.core 未带 IntentCompat）
        @Suppress("DEPRECATION")
        val notification = intent.getParcelableExtra<Notification>(EXTRA_NOTIFICATION)
        pendingIds = intent.getLongArrayExtra(EXTRA_TODO_IDS)?.toList() ?: emptyList()
        reminderDay = intent.getStringExtra(EXTRA_DAY).orEmpty()
        minute = intent.getStringExtra(EXTRA_MINUTE).orEmpty()
        if (notification == null || pendingIds.isEmpty()) {
            stopSelf()
            return
        }
        // startForeground 必须先行（5s 时限）；12+ 后台启动前台服务被拒时在此抛——
        // 修复轮四：被拒不再无声自灭（Receiver 侧只兜 startForegroundService 同步抛的场景，
        // 此处兜 startForeground 才抛的 OEM 变体），按本场口径重查库发兜底响铃通知再收口
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(TodoReminderReceiver.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(TodoReminderReceiver.NOTIFICATION_ID, notification)
            }
        }.onFailure {
            postDegradedAndStop()
            return
        }

        ringer = (ringer ?: ReminderRinger(this)).also { it.start() }
        acquireWakeLock()
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(RING_TIMEOUT_MILLIS)
            onTimeout()
        }
        tryLaunchActivity(pendingIds)
    }

    /**
     * 前台化被拒的收口（修复轮四）：响前口径重查（与 onTimeout 同源——查库失败发不出就
     * 静默，宁可少一声不虚报）→ 发「兜底响铃通知」（渠道级一声 ALARM 铃+震动，服务缺席
     * 时的最小可闻兜底）→ 自灭。挂起重查在 startForeground 5s 时限内绰绰有余（本地库
     * 单表 SELECT）。
     */
    private fun postDegradedAndStop() {
        scope.launch {
            val due = runCatching {
                todoRepository.allTodosOnce().filter {
                    TodoReminderPlanner.isDueForMinute(it, reminderDay, minute)
                }
            }.getOrDefault(emptyList())
            if (due.isNotEmpty()) {
                runCatching {
                    NotificationManagerCompat.from(this@ReminderAlarmService).notify(
                        TodoReminderReceiver.NOTIFICATION_ID,
                        ReminderNotifications.buildAlarmNotification(this@ReminderAlarmService, due, degraded = true),
                    )
                }
            }
            stopSelf()
        }
    }

    /**
     * 亮屏补拉页面：FSI 在亮屏解锁时只出横幅（平台规定）；本应用无障碍服务运行时进程拥有
     * 后台启动 Activity 豁免（ColorOS 亦不拦无障碍宿主），这里补一次直拉。失败（无障碍未开
     * 且 ROM 拦后台启动）则横幅+铃声兜底，点横幅仍进页面——runCatching 不让任何 ROM 崩溃。
     * 与 FSI 的并发拉起由 Activity 的 singleTop + onNewIntent 去重。
     */
    private fun tryLaunchActivity(ids: List<Long>) {
        runCatching {
            startActivity(
                Intent(this, TodoReminderActivity::class.java)
                    .putExtra(TodoReminderActivity.EXTRA_TODO_IDS, ids.toLongArray())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun onTimeout() {
        stopRing()
        val remainingIds = runCatching {
            todoRepository.allTodosOnce().filter {
                TodoReminderPlanner.isDueForMinute(it, reminderDay, minute)
            }.map { it.id }
        }.getOrDefault(pendingIds) // 查库失败时退回原始 ids：宁可多提示不静默丢失
        if (remainingIds.isNotEmpty()) postTimeoutNotice(remainingIds)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /** 60s 收底通知（静默渠道）：点开带剩余 ids 回提醒页仍可完成。 */
    private fun postTimeoutNotice(ids: List<Long>) {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_NOTICE,
            Intent(this, TodoReminderActivity::class.java)
                .putExtra(TodoReminderActivity.EXTRA_TODO_IDS, ids.toLongArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, ReminderChannels.CHANNEL_NOTICE)
            .setSmallIcon(R.drawable.ic_todo_reminder)
            .setContentTitle(getString(R.string.todo_reminder_notification_title))
            .setContentText(getString(R.string.todo_reminder_notice_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this).notify(NOTICE_ID, notification)
    }

    private fun stopRing() {
        timeoutJob?.cancel()
        ringer?.stop()
        wakeLock?.let { runCatching { it.release() } }
        wakeLock = null
    }

    private fun acquireWakeLock() {
        wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "fivesec:reminder-ring",
        )?.also {
            runCatching { it.acquire(RING_TIMEOUT_MILLIS + WAKE_LOCK_SLACK_MILLIS) }
        }
    }

    override fun onDestroy() {
        ringer?.stop()
        wakeLock?.let { runCatching { it.release() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.fivesec.app.reminder.START"
        const val ACTION_STOP_RING = "com.fivesec.app.reminder.STOP_RING"
        const val ACTION_FINISH = "com.fivesec.app.reminder.FINISH"

        const val EXTRA_NOTIFICATION = "notification"
        const val EXTRA_TODO_IDS = "todo_ids"
        const val EXTRA_DAY = "reminder_day"
        const val EXTRA_MINUTE = "reminder_minute"

        /** 响铃上限（原 Activity 侧决策 15 平移）：待办提醒不是起床闹钟，1 分钟足够引起注意。 */
        private const val RING_TIMEOUT_MILLIS = 60_000L

        private const val WAKE_LOCK_SLACK_MILLIS = 5_000L
        private const val REQUEST_NOTICE = 10_013
        private const val NOTICE_ID = 10_014
    }
}
