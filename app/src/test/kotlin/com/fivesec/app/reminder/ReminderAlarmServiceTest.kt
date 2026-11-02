package com.fivesec.app.reminder

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [ReminderAlarmService] 收口契约（STOP_RING 宽限收口修复的回归锚）：
 *  - 冷启动收到 STOP_RING（服务已自灭、用户从收底通知进页后再触摸）：无铃可停，直接自灭——
 *    修复前该分支既不前台化也不自灭，空服务常驻；
 *  - START 缺通知/缺条目、FINISH 冷启动等边界同样必须自灭——服务永不为「无铃空壳」存续。
 *
 * 宽限计时路径（alarmActive=true → 30s 后撤通知自灭）依赖真实协程延时，不在 JVM 单测覆盖，
 * 由 specs/010 quickstart 手测「触摸后按 Home，30s 内通知消失」兜底。
 */
@RunWith(RobolectricTestRunner::class)
class ReminderAlarmServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun service(action: String?): ReminderAlarmService {
        val intent = Intent(context, ReminderAlarmService::class.java)
        if (action != null) intent.action = action
        val controller = Robolectric.buildService(ReminderAlarmService::class.java, intent)
        controller.create().startCommand(0, 1)
        return controller.get()
    }

    @Test
    fun `冷启动收到停铃动作直接自灭不留空服务`() {
        val service = service(ReminderAlarmService.ACTION_STOP_RING)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `冷启动收到完成动作同样自灭`() {
        val service = service(ReminderAlarmService.ACTION_FINISH)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `START缺通知extra视为脏启动自灭`() {
        val service = service(ReminderAlarmService.ACTION_START)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `空action脏启动自灭`() {
        val service = service(null)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }
}
