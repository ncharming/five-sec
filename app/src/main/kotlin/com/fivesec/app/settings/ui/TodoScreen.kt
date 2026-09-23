package com.fivesec.app.settings.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.fivesec.app.R
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.Todo
import com.fivesec.app.domain.model.TodoRule
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
 * 待办页（specs/005-daily-todos；006 增重复规则，首页默认 Tab）：固定每日清单的增删改/启停/今日勾选/规则编辑。
 * 视觉沿用四页统一语言（PageHeader + 白卡行骨架 + FiveSecDialog 弹窗）；名额行已移除——
 * 容量语义由「满员底部提示 + 添加兜底弹窗」承载。标题 ≤200 字：列表单行省略，点条目看只读全文弹窗。
 * 不轮到的日子（specs/006）：行灰显 +「今天不用做」+ 禁勾，条目不消失；规则在编辑弹窗三选一配置。
 * 只读约定：拦截覆盖层上的待办卡片由本页数据派生（快照只含轮到条目），勾选只发生在本页（FR-005）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    viewModel: TodoViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val isFull = rows.size >= TodoRepository.MAX_TODOS

    var showAddDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<TodoRow?>(null) }
    var deleteTarget by remember { mutableStateOf<TodoRow?>(null) }
    var detailTarget by remember { mutableStateOf<TodoRow?>(null) }
    var showLimitDialog by remember { mutableStateOf(false) }

    // 跨日重算：从后台回前台时刷新今日口径（昨天勾的今天自动回未完成）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshToday()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

            // ── 待办卡片列表 ──
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.todos_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.xl),
                )
            } else {
                CardSurface(Modifier.padding(horizontal = Spacing.lg)) {
                    rows.forEachIndexed { index, row ->
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

    // 新建待办弹窗
    TodoEditDialog(
        visible = showAddDialog,
        title = stringResource(R.string.todos_add_title),
        initialText = "",
        initialRule = TodoRule.DAILY,
        onDismiss = { showAddDialog = false },
        onConfirm = { text, rule ->
            if (showAddDialog) viewModel.add(text, rule) // 退场动画期间防重复提交（外壳约定）
            showAddDialog = false
        },
    )

    // 重命名弹窗（specs/006：同时承载规则编辑；规则变了才发定向 UPDATE，文本照旧独立走 rename）
    editTarget?.let { target ->
        val existingRule = TodoRule(target.todo.repeatType, target.todo.repeatDays, target.todo.intervalDays)
        TodoEditDialog(
            visible = true,
            title = stringResource(R.string.todos_edit_title),
            initialText = target.todo.text,
            initialRule = existingRule,
            onDismiss = { editTarget = null },
            onConfirm = { text, rule ->
                if (editTarget != null) {
                    viewModel.rename(target.todo.id, text)
                    if (rule != existingRule) viewModel.setRecurrence(target.todo.id, rule)
                }
                editTarget = null
            },
        )
    }

    // 全文只读弹窗（点条目行触发）：列表是单行省略的展示层截断，完整内容在这里看全
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
        }
    }

    // 删除确认弹窗
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
                    Text(stringResource(R.string.hints_dismiss))
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

/** 卡片行：今日勾选框 + 标题（完成划线/停用弱化，单行省略、点击看全文） + 启用开关 + ⋯菜单（重命名/删除）。 */
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
            if (enabled && !row.dueToday) {
                // 「今天不用做」标注：仅"启用但不轮到"显示——停用行的不可勾原因由开关表达，避免双重误导
                Text(
                    stringResource(R.string.todos_not_due_today),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
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

/**
 * 新建/重命名共用表单弹窗：多行输入（换行折叠为空格）+ 重复规则区（specs/006），200 字硬截断，空白不可确认。
 * 规则区三选一：每天 / 按星期几（多选周几，全空禁存并提示）/ 每 N 天（越界即时收敛显示 2..365）。
 * 弹窗内存态保留用户切走的规则勾选（体验细节），保存只落当前选中规则、其余两列归零（见 data-model 不变式）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TodoEditDialog(
    visible: Boolean,
    title: String,
    initialText: String,
    initialRule: TodoRule,
    onDismiss: () -> Unit,
    onConfirm: (String, TodoRule) -> Unit,
) {
    // rememberSaveable：退场动画期间弹窗仍持内容；重开时由调用方以新 key 重建重置（initialText/initialRule 参与 key）
    var text by rememberSaveable(visible, initialText) { mutableStateOf(initialText) }
    var ruleType by rememberSaveable(visible, initialText) { mutableStateOf(initialRule.repeatType) }
    var repeatDays by rememberSaveable(visible, initialText) { mutableStateOf(initialRule.repeatDays) }
    var intervalText by rememberSaveable(visible, initialText) {
        mutableStateOf(
            initialRule.intervalDays
                .takeIf { initialRule.repeatType == TodoRecurrence.REPEAT_INTERVAL }
                ?.toString()
                ?: "3",
        )
    }

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
                        else -> TodoRule.DAILY
                    }
                    onConfirm(text.trim(), rule)
                },
                enabled = text.trim().isNotBlank() && !weeklyIncomplete,
            ) {
                Text(stringResource(R.string.todos_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.hints_dismiss))
            }
        },
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                // 多行输入只为编辑时长文本可见；换行折叠为空格，保持"单行标题"的存储与展示口径
                val sanitized = raw.replace("\n", " ")
                if (sanitized.length <= TodoRepository.MAX_TEXT_LENGTH) text = sanitized
            },
            placeholder = { Text(stringResource(R.string.todos_input_hint)) },
            minLines = 3,
            maxLines = 6,
            shape = FiveSecTextFieldShape,
            colors = fiveSecTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.hints_char_count, text.length, TodoRepository.MAX_TEXT_LENGTH),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
        )

        // ── 重复规则区（specs/006） ──
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
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
            ) {
                Text(stringResource(R.string.todos_rule_daily))
            }
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_WEEKLY,
                onClick = { ruleType = TodoRecurrence.REPEAT_WEEKLY },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
            ) {
                Text(stringResource(R.string.todos_rule_weekly))
            }
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_INTERVAL,
                onClick = { ruleType = TodoRecurrence.REPEAT_INTERVAL },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
            ) {
                Text(stringResource(R.string.todos_rule_interval))
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
                Text(
                    stringResource(R.string.todos_rule_interval_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
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
