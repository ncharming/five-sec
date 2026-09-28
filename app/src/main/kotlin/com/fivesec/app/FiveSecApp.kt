package com.fivesec.app

import android.app.Application
import android.os.Build
import android.util.Log
import com.fivesec.app.data.seed.DefaultAppSeed
import com.fivesec.app.reminder.ReminderChannels
import com.fivesec.app.reminder.TodoReminderCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class FiveSecApp : Application() {

    @Inject
    lateinit var defaultAppSeed: DefaultAppSeed

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 提醒协调器入口（specs/010）：不走字段注入——字段注入会在构造期于 IO 线程启动 Room 收集器，
     * Robolectric（真实 Application 被实例化）下环境拆除时后台 SQLite 影子查询抛
     * "Illegal connection pointer" 毒化后续测试（AppBlockerAccessibilityService 的 EntryPoint
     * 模式同理）。生产端由此在 [onCreate] 按需构造，测试端由 [isRobolectric] 守卫完全跳过。
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ReminderEntryPoint {
        fun reminderCoordinator(): TodoReminderCoordinator
    }

    override fun onCreate() {
        super.onCreate()
        // 提醒通知渠道（specs/010）：渠道设置不可变，必须在任何通知发出前创建
        // （NotificationManager 影子在 Robolectric 下为受支持空实现，无需守卫）
        ReminderChannels.createChannels(this)
        appScope.launch {
            // 首启种子属尽力而为：失败只记日志不崩溃应用（也避免测试环境下
            // Robolectric SQLite 影子跨线程异常以未捕获异常毒化无关测试）
            runCatching { defaultAppSeed.ensureSeeded() }
                .onFailure { Log.e("FiveSecApp", "ensureSeeded failed", it) }
            // 提醒重排兜底（specs/010 决策 5）：进 App 全量重排一次——自愈强停后闹钟全清、
            // 以及一切「排程与库态失步」的兜底路径；Robolectric 下跳过（见 ReminderEntryPoint 注释）；
            // 失败只记日志（下次进 App 再试）
            if (!isRobolectric()) {
                runCatching {
                    EntryPointAccessors.fromApplication(this@FiveSecApp, ReminderEntryPoint::class.java)
                        .reminderCoordinator()
                        .rescheduleNow()
                }.onFailure { Log.e("FiveSecApp", "reminder reschedule failed", it) }
            }
        }
    }

    private fun isRobolectric(): Boolean =
        Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
}
