package com.fivesec.app.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.fivesec.app.settings.viewmodels.TodoRow
import com.fivesec.app.settings.viewmodels.TodoViewModel
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.FiveSecDialog
import com.fivesec.app.ui.components.FiveSecTextFieldShape
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.components.fiveSecSwitchColors
import com.fivesec.app.ui.components.fiveSecTextFieldColors
import com.fivesec.app.ui.theme.Spacing

/**
 * 待办页（specs/005-daily-todos，首页默认 Tab）：固定每日清单的增删改/启停/今日勾选。
 * 视觉沿用四页统一语言（PageHeader + 白卡行骨架 + FiveSecDialog 弹窗）；名额行已移除——
 * 容量语义由「满员底部提示 + 添加兜底弹窗」承载。标题 ≤200 字：列表单行省略，点条目看只读全文弹窗。
 * 只读约定：拦截覆盖层上的待办卡片由本页数据派生（快照），勾选只发生在本页（FR-005）。
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
        onDismiss = { showAddDialog = false },
        onConfirm = { text ->
            if (showAddDialog) viewModel.add(text) // 退场动画期间防重复提交（外壳约定）
            showAddDialog = false
        },
    )

    // 重命名弹窗
    editTarget?.let { target ->
        TodoEditDialog(
            visible = true,
            title = stringResource(R.string.todos_edit_title),
            initialText = target.todo.text,
            onDismiss = { editTarget = null },
            onConfirm = { text ->
                if (editTarget != null) viewModel.rename(target.todo.id, text)
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
            enabled = enabled, // 停用条目不可勾选（FR-003）
            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.xs)
                .alpha(if (enabled) 1f else 0.62f)
                .clickable(onClick = onShowDetail), // 单行省略是展示层截断：点条目看全文
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

/** 新建/重命名共用表单弹窗：多行输入（只为长文本可见性，换行折叠为空格），200 字硬截断，空白不可确认。 */
@Composable
private fun TodoEditDialog(
    visible: Boolean,
    title: String,
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // rememberSaveable(text)：退场动画期间弹窗仍持内容；重开时由调用方以新 key 重建重置
    var text by rememberSaveable(visible, initialText) { mutableStateOf(initialText) }
    FiveSecDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {
            Button(
                onClick = { onConfirm(text.trim()) },
                enabled = text.trim().isNotBlank(),
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
    }
}
