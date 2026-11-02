package com.fivesec.app.settings.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.reminder.ReminderPermissions
import com.fivesec.app.settings.viewmodels.TodoRow
import com.fivesec.app.settings.viewmodels.TodoViewModel
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.FiveSecDialog
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.theme.Spacing

/**
 * 待办页（specs/005-daily-todos；006 增重复规则；007 两区分区，首页默认 Tab）。
 * 分区（specs/007；2026-10-04 修订）：「今日待办」卡（未过期条目 + 过期但今天轮到的重复类——
 * 维持 id 升序与灰显/停用机制）+「过期待办 (n)」卡（一切过期条目：单次失败存量 + 重复类错过的
 * 轮到日，按错过日升序；无过期整卡不显示）。过期的重复类若今天轮到会双区同时出现：过期区记
 * 失败账（统一口径 2026-11：**过期区不再提供勾选入口**，改展示「今天轮到·在今日区完成」指引——
 * 同一条目的完成行为只有一种统计口径，迟到补完标记只属于「今天不轮到」的过期行），今日区保住
 * 「今天勾掉即补救」的活路（拖延只顺延）；今天不轮到则只在过期区（可补勾/修改/删除）。
 * 今日卡内按重复规则分组（TodoRuleGroups 纯投影）：组序=编辑弹窗四段（每天→每周→每N天→
 * 单次），不同星期集合/不同 N 各自成组（组名自描述，如「每周一、三」），读侧分类与编辑侧
 * 词汇表一致；过期区语义是「失败存量」而非规则分类，不参与分组。
 * 每行标题下副行纯日期（今日区=创建日「—」兜底、过期区=错过的轮到日）；过期行有补勾框
 * （仅今天不轮到的行）、无启用开关，菜单单次「改为今天 / 删除」、重复类「修改 / 删除」。
 * 视觉沿用四页统一语言（PageHeader + 白卡行骨架 + FiveSecDialog 弹窗）；标题 ≤200 字：列表
 * 单行省略，点条目看只读全文弹窗。
 * 只读约定：拦截覆盖层上的待办卡片由本页数据派生（快照只含轮到条目），勾选只发生在本页（FR-005）。
 *
 * 弹窗接线约定（2026-11 修复退场体验）：全部弹窗常驻挂载、[FiveSecDialog] 的 visible 驱动开合
 * （外壳契约：退场动画播完才卸载窗口）——此前编辑/详情/删除用 `?.let` 挂载、关闭即卸载子树，
 * 退场动画播不了；session 号每次打开自增，作为表单 rememberSaveable 的重置键（重开重置、
 * 退场动画期间内容保持，不再闪回空白）。
 */
@Composable
fun TodoScreen(
    viewModel: TodoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val isFull = uiState.todayRows.size + uiState.expiredRows.size >= TodoRepository.MAX_TODOS
    val context = LocalContext.current

    // 弹窗宿主状态：目标 + visible + 会话号（会话号=表单重置键，见类 KDoc）
    var addVisible by remember { mutableStateOf(false) }
    var addSession by remember { mutableStateOf(0) }
    var editTarget by remember { mutableStateOf<TodoRow?>(null) }
    var editVisible by remember { mutableStateOf(false) }
    var editSession by remember { mutableStateOf(0) }
    var deleteTarget by remember { mutableStateOf<TodoRow?>(null) }
    var deleteVisible by remember { mutableStateOf(false) }
    var detailTarget by remember { mutableStateOf<TodoRow?>(null) }
    var detailVisible by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }

    // 提醒权限横幅状态（specs/010）：ON_RESUME 重查（补授权回来即消），不轮询；
    // 仅当存在已设提醒的待办时才展示对应缺失项——不用提醒的用户永不被打扰
    var reminderPermGaps by remember { mutableStateOf(ReminderPermGaps()) }
    val anyReminder = (uiState.todayRows + uiState.expiredRows).any { it.todo.reminderTimes.isNotEmpty() }
    fun refreshReminderPermGaps() {
        reminderPermGaps = ReminderPermGaps(
            notifications = ReminderPermissions.notificationsMissing(context),
            battery = ReminderPermissions.batteryMissing(context),
            fullScreen = ReminderPermissions.fullScreenMissing(context),
        )
    }

    // 13+ 首次设置提醒时刻时请求通知权限（specs/010 决策 16）：结果无论与否保存照常，
    // 拒绝后由横幅持续教育（拒绝不阻断——纯离线工具不拿权限要挟用户）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    fun requestNotifPermissionIfNewlySet(wasEmpty: Boolean, now: String) {
        if (
            wasEmpty && now.isNotEmpty() && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 跨日重算：从后台回前台时刷新今日口径（昨天勾的今天自动回未完成、跨日未完成的进过期区——
    // 单次有效期日过、重复类错过昨天/最近轮到日）；顺带重查提醒权限缺口（授权/降级状态可能在系统设置里被用户改掉）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshToday()
                refreshReminderPermGaps()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { refreshReminderPermGaps() } // 首帧兜底（部分场景 ON_RESUME 先于组合完成）

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── 页头：大标题 + 副标语 + 添加按钮 ──
            PageHeader(
                title = stringResource(R.string.todos_title),
                subtitle = stringResource(R.string.todos_subtitle),
            ) {
                FilledIconButton(
                    onClick = {
                        if (isFull) {
                            showLimitDialog = true
                        } else {
                            addSession++
                            addVisible = true
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.todos_add))
                }
            }

            val allEmpty = uiState.todayRows.isEmpty() && uiState.expiredRows.isEmpty()

            // ── 提醒权限横幅（specs/010 决策 16）：有已设提醒才出现，逐缺失项独立一行 ──
            if (anyReminder) {
                if (reminderPermGaps.notifications) {
                    ReminderPermBanner(
                        text = stringResource(R.string.todo_reminder_banner_notifications),
                        onAction = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
                if (reminderPermGaps.battery) {
                    ReminderPermBanner(
                        text = stringResource(R.string.todo_reminder_banner_battery),
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                        Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        },
                    )
                }
                if (reminderPermGaps.fullScreen) {
                    ReminderPermBanner(
                        text = stringResource(R.string.todo_reminder_banner_fsi),
                        onAction = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT),
                                )
                            }
                        },
                    )
                }
            }

            if (allEmpty) {
                Text(
                    stringResource(R.string.todos_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.xl),
                )
            } else {
                // ── 今日待办卡：未过期 + 过期但今天轮到的重复类（含停用/不轮到/今日一次性），卡内按重复规则分组 ──
                // 行空时整卡不显示（与过期卡对称）：过期重复类今天不轮到时全部沉到过期区，空今日卡只剩噪音
                if (uiState.todayRows.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.todos_section_today))
                    CardSurface(Modifier.padding(horizontal = Spacing.lg)) {
                        TodoRuleGroups.groupByRule(uiState.todayRows).forEachIndexed { groupIndex, group ->
                            if (groupIndex > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                            RuleGroupHeader(ruleGroupLabel(group))
                            group.rows.forEachIndexed { index, row ->
                                if (index > 0) {
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }
                                TodoItemRow(
                                    row = row,
                                    onToggleDone = { viewModel.setCompleted(row.todo.id, !row.doneToday) },
                                    onToggleEnabled = { viewModel.setEnabled(row.todo.id, it) },
                                    onShowDetail = {
                                        detailTarget = row
                                        detailVisible = true
                                    },
                                    onRename = {
                                        editTarget = row
                                        editSession++
                                        editVisible = true
                                    },
                                    onDelete = {
                                        deleteTarget = row
                                        deleteVisible = true
                                    },
                                )
                            }
                        }
                    }
                }

                // ── 过期待办卡：一切过期条目（specs/007 单次 + 2026-10 重复类；无过期整卡不显示） ──
                if (uiState.expiredRows.isNotEmpty()) {
                    SectionLabel(
                        stringResource(R.string.todos_section_expired, uiState.expiredRows.size),
                    )
                    CardSurface(Modifier.padding(horizontal = Spacing.lg)) {
                        uiState.expiredRows.forEachIndexed { index, row ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                            TodoExpiredRow(
                                row = row,
                                onToggleDone = { viewModel.setCompleted(row.todo.id, true, wasExpired = true) },
                                onShowDetail = {
                                    detailTarget = row
                                    detailVisible = true
                                },
                                onRevive = { viewModel.revive(row.todo.id) },
                                onRename = {
                                    editTarget = row
                                    editSession++
                                    editVisible = true
                                },
                                onDelete = {
                                    deleteTarget = row
                                    deleteVisible = true
                                },
                            )
                        }
                    }
                }
            }

            // ── 名额已满提示（前置告知，与拦截应用页同构） ──
            if (isFull) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.todos_full_hint, TodoRepository.MAX_TODOS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }

    // 新建待办弹窗（无完成锚点：下次执行恒为「今天」）
    TodoEditDialog(
        visible = addVisible,
        sessionKey = addSession,
        title = stringResource(R.string.todos_add_title),
        initialText = "",
        initialRule = TodoRule.DAILY,
        initialReminderTimes = "",
        today = today,
        onDismiss = { addVisible = false },
        onConfirm = { text, rule, reminderTimes ->
            if (addVisible) { // 退场动画期间防重复提交（外壳约定）
                viewModel.add(text, rule, reminderTimes)
                requestNotifPermissionIfNewlySet(wasEmpty = true, now = reminderTimes)
            }
            addVisible = false
        },
    )

    // 编辑弹窗（菜单「修改」进；specs/006 起承载规则编辑，规则变了才发定向 UPDATE，文本照旧独立走 rename；
    // 010 起同样承载提醒时刻列表：变了才发定向 UPDATE——「没改不发」的既有口径逐列延续）
    editTarget?.let { target ->
        val existingRule = TodoRule(target.todo.repeatType, target.todo.repeatDays, target.todo.intervalDays)
        TodoEditDialog(
            visible = editVisible,
            sessionKey = editSession,
            title = stringResource(R.string.todos_edit_title),
            initialText = target.todo.text,
            initialRule = existingRule,
            initialReminderTimes = target.todo.reminderTimes,
            today = today,
            lastCompletedDate = target.todo.lastCompletedDate,
            onDismiss = { editVisible = false },
            onConfirm = { text, rule, reminderTimes ->
                if (editVisible) {
                    viewModel.rename(target.todo.id, text)
                    if (rule != existingRule) viewModel.setRecurrence(target.todo.id, rule)
                    if (reminderTimes != target.todo.reminderTimes) {
                        viewModel.setReminderTimes(target.todo.id, reminderTimes)
                    }
                    requestNotifPermissionIfNewlySet(
                        wasEmpty = target.todo.reminderTimes.isEmpty(),
                        now = reminderTimes,
                    )
                }
                editVisible = false
            },
        )
    }

    // 全文只读弹窗（点条目行触发）：列表是单行省略的展示层截断，完整内容 + 日期在这里看全
    detailTarget?.let { target ->
        FiveSecDialog(
            visible = detailVisible,
            onDismissRequest = { detailVisible = false },
            title = stringResource(R.string.todos_detail_title),
            confirmButton = {
                Button(onClick = { detailVisible = false }) {
                    Text(stringResource(R.string.todos_detail_close))
                }
            },
        ) {
            Text(
                target.todo.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                target.dateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }

    // 删除确认弹窗（今日/过期两区共用）
    deleteTarget?.let { target ->
        FiveSecDialog(
            visible = deleteVisible,
            onDismissRequest = { deleteVisible = false },
            title = stringResource(R.string.todos_delete_confirm),
            confirmButton = {
                Button(onClick = {
                    if (deleteVisible) viewModel.remove(target.todo.id)
                    deleteVisible = false
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteVisible = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            Text(
                target.todo.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // 名额已满提示弹窗（点 + 时兜底）
    FiveSecDialog(
        visible = showLimitDialog,
        onDismissRequest = { showLimitDialog = false },
        title = stringResource(R.string.common_notice),
        confirmButton = {
            Button(onClick = { showLimitDialog = false }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    ) {
        Text(
            stringResource(R.string.todos_full_hint, TodoRepository.MAX_TODOS),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
