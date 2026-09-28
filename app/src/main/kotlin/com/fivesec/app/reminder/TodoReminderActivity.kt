package com.fivesec.app.reminder

import android.app.PendingIntent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.fivesec.app.R
import com.fivesec.app.ui.theme.FiveSecTheme
import com.fivesec.app.ui.theme.Spacing
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 全屏提醒页（specs/010 决策 6/14/15）：full-screen intent 通知拉起的类闹钟界面——锁屏之上显示、
 * 自动亮屏、常亮、不进最近任务；ReminderRinger 持续响铃+震动。
 *
 * 停铃三路径（先到先赢）：① 任意交互（勾一条/关闭）——人已到场，铃声使命完成；
 * ② 60 秒无操作自动停铃——收进静默通知（点开带剩余 ids 回本页仍可完成）；
 * ③ 全部勾完——列表空即自动关页。返回手势=关闭（走同一路径停铃）。
 */
@AndroidEntryPoint
class TodoReminderActivity : ComponentActivity() {

    private val viewModel: TodoReminderViewModel by viewModels()
    private lateinit var ringer: ReminderRinger
    private var timeoutJob: Job? = null

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

        ringer = ReminderRinger(this)
        ringer.start()
        viewModel.load(intent.getLongArrayExtra(EXTRA_TODO_IDS)?.toList() ?: emptyList())

        // 60s 无操作：停铃 → 静默通知（剩余条目）→ 关页
        timeoutJob = lifecycleScope.launch {
            delay(RING_TIMEOUT_MILLIS)
            onTimeout()
        }
        // 全部勾完：自动关页（ringer 在 onDestroy 统一收口）
        lifecycleScope.launch {
            viewModel.state.collect { state ->
                if (state.loaded && state.rows.isEmpty()) finish()
            }
        }

        setContent {
            FiveSecTheme {
                // collectAsStateWithLifecycle：勾选完成的行移除要驱动重组（state.value 快照不会触发）
                val state by viewModel.state.collectAsStateWithLifecycle()
                ReminderScreen(
                    state = state,
                    onComplete = { id ->
                        stopRinging() // 人已到场：铃使命完成，页留着让人勾完
                        viewModel.complete(id)
                    },
                    onClose = { finish() },
                )
            }
        }
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        ringer.stop()
        super.onDestroy()
    }

    private fun stopRinging() {
        timeoutJob?.cancel()
        ringer.stop()
    }

    private fun onTimeout() {
        ringer.stop()
        val pending = viewModel.pendingIds()
        if (pending.isNotEmpty()) postTimeoutNotice(pending.toLongArray())
        finish()
    }

    /** 60s 超时收底：静默通知（notice 渠道），点开带剩余 ids 回本页。 */
    private fun postTimeoutNotice(ids: LongArray) {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQUEST_NOTICE,
            Intent(this, TodoReminderActivity::class.java)
                .putExtra(EXTRA_TODO_IDS, ids)
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

    companion object {
        const val EXTRA_TODO_IDS = "todo_ids"

        /** 响铃上限（决策 15）：待办提醒不是起床闹钟，1 分钟足够引起注意。 */
        private const val RING_TIMEOUT_MILLIS = 60_000L

        private const val REQUEST_NOTICE = 10_013
        private const val NOTICE_ID = 10_014
    }
}

/** 提醒页界面：竖向居中单卡——标题（含当前时刻）+ 条目列表（Checkbox 勾完成）+ 关闭按钮。 */
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
                                Checkbox(checked = false, onCheckedChange = { onComplete(row.id) })
                                Text(
                                    row.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(start = Spacing.xs),
                                )
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

private fun currentMinuteLabel(): String =
    "%02d:%02d".format(LocalTime.now().hour, LocalTime.now().minute)
