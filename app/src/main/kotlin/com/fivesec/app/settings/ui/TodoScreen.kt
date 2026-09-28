package com.fivesec.app.settings.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.fivesec.app.R
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.reminder.ReminderPermissions
import com.fivesec.app.settings.viewmodels.TodoRow
import com.fivesec.app.settings.viewmodels.TodoViewModel
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.FiveSecDialog
import com.fivesec.app.ui.components.FiveSecTextFieldShape
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.components.fiveSecSwitchColors
import com.fivesec.app.ui.components.fiveSecTextFieldColors
import com.fivesec.app.ui.theme.Spacing
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek

/**
 * 待办页（specs/005-daily-todos；006 增重复规则；007 两区分区，首页默认 Tab）。
 * 分区（specs/007）：「今日待办」卡（一切未过期条目，维持 id 升序与灰显/停用机制）+
 * 「过期待办 (n)」卡（一次性失败存量，有效期日升序；无过期整卡不显示）。
 * 每行标题下副行纯日期（今日区=创建日「—」兜底、过期区=有效期日）；过期行无勾选框/开关，
 * 仅「改为今天 / 删除」——失败可重试，但无迟到补勾（用户拍板口径）。
 * 视觉沿用四页统一语言（PageHeader + 白卡行骨架 + FiveSecDialog 弹窗）；名额行已移除——
 * 容量语义由「满员底部提示 + 添加兜底弹窗」承载。标题 ≤200 字：列表单行省略，点条目看只读全文弹窗。
 * 只读约定：拦截覆盖层上的待办卡片由本页数据派生（快照只含轮到条目），勾选只发生在本页（FR-005）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    viewModel: TodoViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val isFull = uiState.todayRows.size + uiState.expiredRows.size >= TodoRepository.MAX_TODOS
    val context = LocalContext.current

    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<TodoRow?>(null) }
    var deleteTarget by remember { mutableStateOf<TodoRow?>(null) }
    var detailTarget by remember { mutableStateOf<TodoRow?>(null) }
    var showLimitDialog by remember { mutableStateOf(false) }

    // 提醒权限横幅状态（specs/010）：ON_RESUME 重查（补授权回来即消），不轮询；
    // 仅当存在已设提醒的待办时才展示对应缺失项——不用提醒的用户永不被打扰
    var reminderPermGaps by remember { mutableStateOf(ReminderPermGaps()) }
    val anyReminder = (uiState.todayRows + uiState.expiredRows).any { it.todo.reminderTime.isNotEmpty() }
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

    // 跨日重算：从后台回前台时刷新今日口径（昨天勾的今天自动回未完成、一次性跨日进过期区）；
    // 顺带重查提醒权限缺口（授权/降级状态可能在系统设置里被用户改掉）
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
                            showAddDialog = true
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
                // ── 今日待办卡：一切未过期条目（含停用/不轮到/今日一次性） ──
                SectionLabel(stringResource(R.string.todos_section_today))
                CardSurface(Modifier.padding(horizontal = Spacing.lg)) {
                    uiState.todayRows.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        TodoItemRow(
                            row = row,
                            onToggleDone = { viewModel.setCompleted(row.todo.id, !row.doneToday) },
                            onToggleEnabled = { viewModel.setEnabled(row.todo.id, it) },
                            onShowDetail = { detailTarget = row },
                            onRename = { editTarget = row },
                            onDelete = { deleteTarget = row },
                        )
                    }
                }

                // ── 过期待办卡：一次性失败存量（specs/007；无过期整卡不显示） ──
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
                                onShowDetail = { detailTarget = row },
                                onRevive = { viewModel.revive(row.todo.id) },
                                onDelete = { deleteTarget = row },
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
        visible = showAddDialog,
        title = stringResource(R.string.todos_add_title),
        initialText = "",
        initialRule = TodoRule.DAILY,
        initialReminderTime = "",
        today = today,
        onDismiss = { showAddDialog = false },
        onConfirm = { text, rule, reminderTime ->
            if (showAddDialog) { // 退场动画期间防重复提交（外壳约定）
                viewModel.add(text, rule, reminderTime)
                requestNotifPermissionIfNewlySet(wasEmpty = true, now = reminderTime)
            }
            showAddDialog = false
        },
    )

    // 编辑弹窗（菜单「修改」进；specs/006 起承载规则编辑，规则变了才发定向 UPDATE，文本照旧独立走 rename；
    // 010 起同样承载提醒时刻：变了才发定向 UPDATE——「没改不发」的既有口径逐列延续）
    editTarget?.let { target ->
        val existingRule = TodoRule(target.todo.repeatType, target.todo.repeatDays, target.todo.intervalDays)
        TodoEditDialog(
            visible = true,
            title = stringResource(R.string.todos_edit_title),
            initialText = target.todo.text,
            initialRule = existingRule,
            initialReminderTime = target.todo.reminderTime,
            today = today,
            lastCompletedDate = target.todo.lastCompletedDate,
            onDismiss = { editTarget = null },
            onConfirm = { text, rule, reminderTime ->
                if (editTarget != null) {
                    viewModel.rename(target.todo.id, text)
                    if (rule != existingRule) viewModel.setRecurrence(target.todo.id, rule)
                    if (reminderTime != target.todo.reminderTime) {
                        viewModel.setReminderTime(target.todo.id, reminderTime)
                    }
                    requestNotifPermissionIfNewlySet(
                        wasEmpty = target.todo.reminderTime.isEmpty(),
                        now = reminderTime,
                    )
                }
                editTarget = null
            },
        )
    }

    // 全文只读弹窗（点条目行触发）：列表是单行省略的展示层截断，完整内容 + 日期在这里看全
    detailTarget?.let { target ->
        FiveSecDialog(
            visible = true,
            onDismissRequest = { detailTarget = null },
            title = stringResource(R.string.todos_detail_title),
            confirmButton = {
                Button(onClick = { detailTarget = null }) {
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
            visible = true,
            onDismissRequest = { deleteTarget = null },
            title = stringResource(R.string.todos_delete_confirm),
            confirmButton = {
                Button(onClick = {
                    if (deleteTarget != null) viewModel.remove(target.todo.id)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
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

/** 分区小节标题（specs/007 两卡分区）：统一左对齐 labelMedium 弱化色。 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = Spacing.lg,
            end = Spacing.lg,
            top = Spacing.md,
            bottom = Spacing.xs,
        ),
    )
}

/** 今日区行：今日勾选框 + 标题（完成划线/停用弱化，单行省略、点击看全文）+ 副行日期 + 启用开关 + ⋯（修改/删除）。 */
@Composable
private fun TodoItemRow(
    row: TodoRow,
    onToggleDone: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onShowDetail: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val enabled = row.todo.isEnabled
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = row.doneToday,
            onCheckedChange = { onToggleDone() },
            enabled = enabled && row.dueToday, // 停用或不轮到都不可勾（FR-003 / specs/006 FR-005）
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.xs)
                .alpha(
                    when {
                        !enabled -> 0.62f // 停用弱化（既有语义）
                        !row.dueToday -> 0.45f // 不轮到灰显（specs/006）
                        else -> 1f
                    },
                )
                .clickable(onClick = onShowDetail), // 单行省略是展示层截断：点条目看全文（任何状态可用）
        ) {
            Text(
                row.todo.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (row.doneToday) TextDecoration.LineThrough else null,
                color = if (row.doneToday) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副行（specs/007）：创建日（老条目「—」）；"启用但不轮到"追加「今天不用做」——
            // 停用行的不可勾原因由开关表达，避免双重误导；010 起已设提醒追加「提醒 HH:mm」
            val notDueLabel = if (enabled && !row.dueToday) {
                " · " + stringResource(R.string.todos_not_due_today)
            } else {
                ""
            }
            val reminderLabel = if (row.todo.reminderTime.isNotEmpty()) {
                " · " + stringResource(R.string.todos_reminder_inline, row.todo.reminderTime)
            } else {
                ""
            }
            Text(
                row.dateLabel + notDueLabel + reminderLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggleEnabled,
            colors = fiveSecSwitchColors(),
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.todos_menu_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.todos_menu_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 过期区行（specs/007）：标题 + 副行有效期日（哪天失败的）+ ⋯（改为今天/删除）。
 *  无勾选框（迟到补勾=自欺，用户拍板否决）、无启用开关（过期无"明天"语义，开关只剩误导）。 */
@Composable
private fun TodoExpiredRow(
    row: TodoRow,
    onShowDetail: () -> Unit,
    onRevive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(0.75f) // 失败存量弱化一档，但保持可读（要点开看全文/操作）
                .clickable(onClick = onShowDetail),
        ) {
            Text(
                row.todo.text,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // 有效期日（specs/007：过期归因看"哪天失败的"）；010 提醒角标照常展示（配置可见性，非会响承诺）
                row.dateLabel + (
                    if (row.todo.reminderTime.isNotEmpty()) {
                        " · " + stringResource(R.string.todos_reminder_inline, row.todo.reminderTime)
                    } else {
                        ""
                    }
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.todos_menu_revive)) },
                    onClick = {
                        menuOpen = false
                        onRevive()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.todos_menu_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/**
 * 新建/编辑共用表单弹窗：多行输入（存真实换行 \n，详见 onValueChange 注释）+ 重复规则区（specs/006 三选一；
 * specs/007 扩为四选一，新增「单次」）+ 提醒时刻区（specs/010：可选 HH:mm，TimePicker 24h，
 * 可清除），200 字硬截断，空白不可确认。
 * 规则区：每天 / 每周几（多选周几，全空禁存并提示）/ 每N天（越界即时收敛显示 2..365，
 * 下方展示下次执行日——锚点=最近完成日，从未完成=今天）/ 单次（原「仅今天」，只在有效期日
 * 当天轮到，跨日未完成进过期分类）。分段按钮不显示选中 ✓（四段等宽排版 + 选中态已由底色表达）。
 * 弹窗内存态保留用户切走的规则勾选（体验细节），保存只落当前选中规则、其余两列归零（见 data-model 不变式）。
 * 提醒区：未设置态点击弹 TimePicker；已设置态展示 HH:mm + 「清除」；保存时与初始值不同才发定向 UPDATE
 * （与规则区同口径）；单次规则下提示「只在有效期日当天响」。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TodoEditDialog(
    visible: Boolean,
    title: String,
    initialText: String,
    initialRule: TodoRule,
    initialReminderTime: String,
    today: String,
    onDismiss: () -> Unit,
    onConfirm: (String, TodoRule, String) -> Unit,
    lastCompletedDate: String = "", // 新建传缺省（从未完成）；编辑传最近完成日（下次执行日的锚点）
) {
    // rememberSaveable：退场动画期间弹窗仍持内容；重开时由调用方以新 key 重建重置。
    // 注意 key 必须可存 Bundle（rememberSaveable 会持久化 inputs）：initialRule 是数据类，
    // 以其 toString（三个 Int 字段、确定性）作 key 参与变化检测，而非对象本身
    val ruleKey = initialRule.toString()
    var text by rememberSaveable(visible, initialText, ruleKey) { mutableStateOf(initialText) }
    var ruleType by rememberSaveable(visible, initialText, ruleKey) { mutableStateOf(initialRule.repeatType) }
    var repeatDays by rememberSaveable(visible, initialText, ruleKey) { mutableStateOf(initialRule.repeatDays) }
    var intervalText by rememberSaveable(visible, initialText, ruleKey) {
        mutableStateOf(
            initialRule.intervalDays
                .takeIf { initialRule.repeatType == TodoRecurrence.REPEAT_INTERVAL }
                ?.toString()
                ?: "3",
        )
    }
    var reminderTime by rememberSaveable(visible, initialText, ruleKey, initialReminderTime) {
        mutableStateOf(initialReminderTime)
    }
    var showTimePicker by remember { mutableStateOf(false) }

    val weeklyIncomplete = ruleType == TodoRecurrence.REPEAT_WEEKLY && repeatDays == 0
    val intervalDays = (intervalText.toIntOrNull() ?: TodoRecurrence.MIN_INTERVAL_DAYS)
        .coerceIn(TodoRecurrence.MIN_INTERVAL_DAYS, TodoRecurrence.MAX_INTERVAL_DAYS)

    FiveSecDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            Button(
                onClick = {
                    val rule = when (ruleType) {
                        TodoRecurrence.REPEAT_WEEKLY -> {
                            val days = DayOfWeek.values()
                                .filter { repeatDays and TodoRecurrence.bitOf(it) != 0 }
                                .toSet()
                            if (days.isEmpty()) return@Button // 防御：按钮已禁用，正常不可达
                            TodoRule.weekly(days)
                        }

                        TodoRecurrence.REPEAT_INTERVAL -> TodoRule.interval(intervalDays)
                        TodoRecurrence.REPEAT_ONCE -> TodoRule.ONCE
                        else -> TodoRule.DAILY
                    }
                    onConfirm(text.trim(), rule, reminderTime)
                },
                enabled = text.trim().isNotBlank() && !weeklyIncomplete,
            ) {
                Text(stringResource(R.string.todos_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                // 真实换行（2026-09 口径修订，取代「换行折叠为空格」）：回车存 \n——
                // 详情弹窗按行渲染、列表单行省略、拦截卡片取首个非空行（TodoCardText）；
                // 保存时 trim 剥掉首尾空白行；200 字预算含换行符，不另立计数规则
                if (raw.length <= TodoRepository.MAX_TEXT_LENGTH) text = raw
            },
            placeholder = { Text(stringResource(R.string.todos_input_hint)) },
            minLines = 3,
            maxLines = 6,
            shape = FiveSecTextFieldShape,
            colors = fiveSecTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.common_char_count, text.length, TodoRepository.MAX_TEXT_LENGTH),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        )

        // ── 重复规则区（specs/006；007 扩四选一） ──
        Text(
            stringResource(R.string.todos_rule_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
        )
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        ) {
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_DAILY,
                onClick = { ruleType = TodoRecurrence.REPEAT_DAILY },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                icon = {}, // 选中态由底色表达，不再画 ✓——省宽度，四段文案不再换行重叠
            ) {
                Text(stringResource(R.string.todos_rule_daily))
            }
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_WEEKLY,
                onClick = { ruleType = TodoRecurrence.REPEAT_WEEKLY },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                icon = {},
            ) {
                Text(stringResource(R.string.todos_rule_weekly))
            }
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_INTERVAL,
                onClick = { ruleType = TodoRecurrence.REPEAT_INTERVAL },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                icon = {},
            ) {
                Text(stringResource(R.string.todos_rule_interval))
            }
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_ONCE,
                onClick = { ruleType = TodoRecurrence.REPEAT_ONCE },
                shape = SegmentedButtonDefaults.itemShape(index = 3, count = 4),
                icon = {},
            ) {
                Text(stringResource(R.string.todos_rule_once))
            }
        }

        when (ruleType) {
            TodoRecurrence.REPEAT_WEEKLY -> {
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    DayOfWeek.values().forEach { day ->
                        val bit = TodoRecurrence.bitOf(day)
                        FilterChip(
                            selected = repeatDays and bit != 0,
                            onClick = { repeatDays = repeatDays xor bit },
                            label = { Text(todoRuleDayLabel(day)) },
                        )
                    }
                }
                if (weeklyIncomplete) {
                    Text(
                        stringResource(R.string.todos_rule_weekly_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
            }

            TodoRecurrence.REPEAT_INTERVAL -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs),
                ) {
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { raw ->
                            // 只留数字 + 越界即时收敛显示（输 1 → 2、输 999 → 365）；清空允许（保存时兜底 MIN）
                            val digits = raw.filter { it.isDigit() }.take(3)
                            intervalText = if (digits.isEmpty()) {
                                ""
                            } else {
                                digits.toInt()
                                    .coerceIn(TodoRecurrence.MIN_INTERVAL_DAYS, TodoRecurrence.MAX_INTERVAL_DAYS)
                                    .toString()
                            }
                        },
                        label = { Text(stringResource(R.string.todos_rule_interval)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = FiveSecTextFieldShape,
                        colors = fiveSecTextFieldColors(),
                        modifier = Modifier.width(120.dp),
                    )
                    Text(stringResource(R.string.todos_rule_interval_days))
                }
                // 下次执行日（用户要求：选中每N天时展示）：与灰显判定同源的纯函数算出，随 N 输入即时联动；
                // 已到期/从未完成收敛为「今天」，today 非法（防御不可达）返回 null 整行隐藏
                val nextDue = TodoRecurrence.nextIntervalDueDate(lastCompletedDate, intervalDays, today)
                if (nextDue != null) {
                    Text(
                        stringResource(
                            R.string.todos_rule_interval_next,
                            if (nextDue == today) {
                                stringResource(R.string.todos_rule_interval_next_today)
                            } else {
                                nextDue
                            },
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                Text(
                    stringResource(R.string.todos_rule_interval_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }

            TodoRecurrence.REPEAT_ONCE -> {
                Text(
                    stringResource(R.string.todos_rule_once_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }

        // ── 提醒时刻区（specs/010）：可选；未设置点击弹 TimePicker，已设置可清除 ──
        Text(
            stringResource(R.string.todos_reminder_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        ) {
            Text(
                if (reminderTime.isEmpty()) stringResource(R.string.todos_reminder_none) else reminderTime,
                style = MaterialTheme.typography.bodyMedium,
                color = if (reminderTime.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .clickable { showTimePicker = true },
            )
            if (reminderTime.isNotEmpty()) {
                TextButton(onClick = { reminderTime = "" }) {
                    Text(stringResource(R.string.todos_reminder_clear))
                }
            }
        }
        if (ruleType == TodoRecurrence.REPEAT_ONCE && reminderTime.isNotEmpty()) {
            Text(
                stringResource(R.string.todos_reminder_once_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }

    // 提醒时刻选择器（specs/010）：统一弹窗外壳包 Material3 TimePicker（24 小时制、分钟精度）；
    // 确认回写 HH:mm（秒恒 00 不暴露），取消不动
    if (showTimePicker) {
        val initial = reminderTime.takeIf { it.isNotEmpty() }?.split(":")
        val pickerState = rememberTimePickerState(
            initialHour = initial?.getOrNull(0)?.toIntOrNull() ?: DEFAULT_REMINDER_HOUR,
            initialMinute = initial?.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true,
        )
        FiveSecDialog(
            visible = true,
            onDismissRequest = { showTimePicker = false },
            title = stringResource(R.string.todos_reminder_pick_title),
            confirmButton = {
                Button(onClick = {
                    reminderTime = "%02d:%02d".format(pickerState.hour, pickerState.minute)
                    showTimePicker = false
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            TimePicker(state = pickerState)
        }
    }
}

/** 周几 FilterChip 文案（资源引用，中文第一语言）。 */
@Composable
private fun todoRuleDayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> stringResource(R.string.todos_rule_dow_1)
    DayOfWeek.TUESDAY -> stringResource(R.string.todos_rule_dow_2)
    DayOfWeek.WEDNESDAY -> stringResource(R.string.todos_rule_dow_3)
    DayOfWeek.THURSDAY -> stringResource(R.string.todos_rule_dow_4)
    DayOfWeek.FRIDAY -> stringResource(R.string.todos_rule_dow_5)
    DayOfWeek.SATURDAY -> stringResource(R.string.todos_rule_dow_6)
    else -> stringResource(R.string.todos_rule_dow_7)
}

/** 提醒权限缺口快照（specs/010 横幅判定源）：三项各自独立（ReminderPermissions 同口径）。 */
private data class ReminderPermGaps(
    val notifications: Boolean = false,
    val battery: Boolean = false,
    val fullScreen: Boolean = false,
)

/** 提醒权限横幅行：说明文案 + 「去开启」；出现/消失由 ON_RESUME 重查驱动，不跟踪一次性动作结果。 */
@Composable
private fun ReminderPermBanner(text: String, onAction: () -> Unit) {
    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) {
                Text(stringResource(R.string.todo_reminder_banner_action))
            }
        }
    }
}

/** TimePicker 缺省初始小时（specs/010）：晚间自律场景的常见提醒档位，纯 UX 缺省无语义。 */
private const val DEFAULT_REMINDER_HOUR = 20
