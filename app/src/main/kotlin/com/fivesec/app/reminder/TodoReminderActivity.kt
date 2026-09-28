package com.fivesec.app.reminder

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.fivesec.app.R
import com.fivesec.app.ui.theme.FiveSecTheme
import com.fivesec.app.ui.theme.Spacing
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalTime
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏提醒页（specs/010 决策 6/14/15）：类闹钟界面——锁屏之上显示、自动亮屏、常亮、不进最近
 * 任务。铃声与震动由 ReminderAlarmService 持有（修复轮：原绑在本页 onCreate，FSI 亮屏降级/
 * 被拒时只亮横幅不响）；本页只负责展示与完成操作，任何交互经服务 ACTION_STOP_RING 停铃，
 * 关页（关闭按钮/返回/全勾自动关）在 [finish] 统一收口发 ACTION_FINISH 撤服务。
 *
 * singleTop：FSI 拉起与服务亮屏补拉并发去重；第二场提醒命中已开页面走 [onNewIntent] 重装。
 * 条目展示**完整内容**（多行原文、不截断）+ 每条「完成」按钮——一键直完成（与待办页同款
 * setCompleted 双写口径）。
 */
@AndroidEntryPoint
class TodoReminderActivity : ComponentActivity() {

    private val viewModel: TodoReminderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏之上显示 + 自动亮屏（26 用 window flags，27+ 走专用 API）+ 期间常亮
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 人已到场：清掉 FSI/前台通知——页本身就是提醒本体（服务仍在响铃，交互即停）
        NotificationManagerCompat.from(this).cancel(TodoReminderReceiver.NOTIFICATION_ID)

        viewModel.load(todoIds())

        // 全部勾完：自动关页（finish 收口统一撤服务）
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (state.loaded && state.rows.isEmpty()) finish()
            }
        }

        setContent {
            FiveSecTheme {
                // collectAsStateWithLifecycle：完成行的移除要驱动重组（state.value 快照不会触发）
                val state by viewModel.state.collectAsStateWithLifecycle()
                ReminderScreen(
                    state = state,
                    onComplete = { id -> viewModel.complete(id) },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // 已开页面被第二场提醒复用（singleTop）：重装新一场的条目
        viewModel.load(todoIds(), force = true)
        NotificationManagerCompat.from(this).cancel(TodoReminderReceiver.NOTIFICATION_ID)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // 任意触摸（勾选/滚动/返回……）：人已到场，铃使命完成（幂等）
        sendServiceAction(this, ReminderAlarmService.ACTION_STOP_RING)
    }

    override fun finish() {
        // 关页统一收口：关闭按钮 / 返回手势 / 全勾自动关——撤服务（停铃+清通知+自灭）。
        // 页面可能在后台时刻 finish，startService 或被系统拒绝：runCatching，服务有 60s 自愈
        sendServiceAction(this, ReminderAlarmService.ACTION_FINISH)
        super.finish()
    }

    private fun todoIds(): List<Long> =
        intent.getLongArrayExtra(EXTRA_TODO_IDS)?.toList() ?: emptyList()

    companion object {
        const val EXTRA_TODO_IDS = "todo_ids"

        /** 显式 startService 语义：服务已在前台运行，动作即达；未运行时服务自灭，无副作用。 */
        private fun sendServiceAction(context: Context, action: String) {
            runCatching {
                context.startService(
                    Intent(context, ReminderAlarmService::class.java).setAction(action),
                )
            }
        }
    }
}

/** 提醒页界面：竖向居中单卡（可滚动）——标题（含当前时刻）+ 条目列表（完整内容+完成按钮）+ 关闭。 */
@Composable
private fun ReminderScreen(
    state: TodoReminderViewModel.ReminderUiState,
    onComplete: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            var nowMinute by remember { mutableStateOf(currentMinuteLabel()) }
            LaunchedEffect(Unit) {
                while (true) {
                    nowMinute = currentMinuteLabel()
                    delay(30_000) // 分钟跳变粒度刷新，只为标题展示
                }
            }
            Text(
                stringResource(R.string.todo_reminder_screen_title, nowMinute),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(Spacing.lg))
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(),
            ) {
                Column(Modifier.padding(Spacing.lg)) {
                    if (!state.loaded) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(Spacing.md),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }
                    } else {
                        state.rows.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 完整内容不截断（多行原文保留）——提醒页是「看全 + 一键完成」的地方
                                Text(
                                    row.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = Spacing.sm),
                                )
                                Button(onClick = { onComplete(row.id) }) {
                                    Text(stringResource(R.string.todo_reminder_action_complete))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                    Button(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(stringResource(R.string.todo_reminder_close))
                    }
                }
            }
        }
    }
}

private fun currentMinuteLabel(): String {
    val now = LocalTime.now()
    // Locale.ROOT：任何系统语言下标题时刻都是 ASCII 数字（展示位，但与库口径保持同一习惯）
    return String.format(Locale.ROOT, "%02d:%02d", now.hour, now.minute)
}
