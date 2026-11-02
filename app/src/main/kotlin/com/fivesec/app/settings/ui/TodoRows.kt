package com.fivesec.app.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fivesec.app.R
import com.fivesec.app.reminder.TodoReminderPlanner
import com.fivesec.app.settings.viewmodels.TodoRow
import com.fivesec.app.ui.components.fiveSecSwitchColors
import com.fivesec.app.ui.theme.Spacing
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek

/**
 * 待办页行组件与分组标签（自 TodoScreen 拆出，2026-11：1161 行单文件按职责分层）。
 * 渲染口径不变：今日区行=勾选+划线/灰显+开关+菜单；过期区行=失败存量弱化展示。
 */

/** 分区小节标题（specs/007 两卡分区）：统一左对齐 labelMedium 弱化色。 */
@Composable
internal fun SectionLabel(text: String) {
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

/** 今日卡内规则分组头：与区标题同字体但缩进对齐行内容（md），组间靠分隔线+组头双重区隔。 */
@Composable
internal fun RuleGroupHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = Spacing.md,
            end = Spacing.md,
            top = Spacing.md,
            bottom = Spacing.xs,
        ),
    )
}

/**
 * 分组名与编辑弹窗同一词汇表：每天 / 每周一、三 / 每 N 天 / 单次——读侧分类不发明新词。
 * 防御：周几空集（脏数据，表单已拦）给类型名「每周几」不伪造天数；未知类型兜底按每天档
 * 命名（与 isDue 的脏值兜底同思路：不让条目凭空消失/错位）。
 */
@Composable
internal fun ruleGroupLabel(group: TodoRuleGroups.Group): String = when (group.repeatType) {
    TodoRecurrence.REPEAT_WEEKLY -> {
        val days = DayOfWeek.values().filter { group.repeatDays and TodoRecurrence.bitOf(it) != 0 }
        if (days.isEmpty()) {
            stringResource(R.string.todos_rule_weekly)
        } else {
            // 标签须先在组合上下文取好（joinToString 的 transform 非组合上下文），
            // forEach 是 inline——lambda 内 stringResource 可用
            val dayNames = ArrayList<String>(days.size)
            days.forEach { dayNames.add(todoRuleDayLabel(it)) }
            stringResource(R.string.todos_group_weekly, dayNames.joinToString("、"))
        }
    }
    TodoRecurrence.REPEAT_INTERVAL -> stringResource(R.string.todos_group_interval, group.intervalDays)
    TodoRecurrence.REPEAT_ONCE -> stringResource(R.string.todos_rule_once)
    else -> stringResource(R.string.todos_rule_daily)
}

/** 周几 FilterChip 文案（资源引用，中文第一语言）；编辑弹窗的周几选择 chips 同源复用。 */
@Composable
internal fun todoRuleDayLabel(day: DayOfWeek): String = when (day) {
    DayOfWeek.MONDAY -> stringResource(R.string.todos_rule_dow_1)
    DayOfWeek.TUESDAY -> stringResource(R.string.todos_rule_dow_2)
    DayOfWeek.WEDNESDAY -> stringResource(R.string.todos_rule_dow_3)
    DayOfWeek.THURSDAY -> stringResource(R.string.todos_rule_dow_4)
    DayOfWeek.FRIDAY -> stringResource(R.string.todos_rule_dow_5)
    DayOfWeek.SATURDAY -> stringResource(R.string.todos_rule_dow_6)
    else -> stringResource(R.string.todos_rule_dow_7)
}

/** 行内角标的多时刻展示形（013）：CSV → 「20:00、21:00、22:00」（顿号连接，中文第一语言）。 */
internal fun reminderTimesForDisplay(raw: String): String =
    TodoReminderPlanner.parseReminderTimes(raw).joinToString("、")

/** 今日区行：今日勾选框 + 标题（完成划线/停用弱化，单行省略、点击看全文）+ 副行日期 + 启用开关 + ⋯（修改/删除）。
 *  勾选框启用条件（2026-10-04 扩展）：轮到 可勾可取消；不轮到不可勾（FR-003 / specs/006 FR-005），
 *  但「今天已完成」的不轮到行可取消——过期补勾的撤销路径（取消即回过期区），只关不清自来账。 */
@Composable
internal fun TodoItemRow(
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
            enabled = enabled && (row.dueToday || row.doneToday), // 停用不可勾；不轮到仅已完成的可取消
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
            // 停用行的不可勾原因由开关表达，避免双重误导；已完成的今天行不追加（划线已表达，
            // 迟到补勾的不轮到行再说"今天不用做"自相矛盾）；010 起已设提醒追加「提醒 HH:mm」
            val notDueLabel = if (enabled && !row.dueToday && !row.doneToday) {
                " · " + stringResource(R.string.todos_not_due_today)
            } else {
                ""
            }
            val reminderLabel = if (row.todo.reminderTimes.isNotEmpty()) {
                " · " + stringResource(R.string.todos_reminder_inline, reminderTimesForDisplay(row.todo.reminderTimes))
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
        RowMenu(
            hasRevive = false,
            hasRename = true,
            onRename = onRename,
            onRevive = {},
            onDelete = onDelete,
        )
    }
}

/** 过期区行（specs/007；2026-10 重复类入区 + 可补勾）：标题 + 副行错过的轮到日 + ⋯。
 *  补勾框（统一口径 2026-11）：**只给「今天不轮到」的过期行**——写 lastCompletedDate=今天并给
 *  完成事件打「过期补完」标记（统计显式区分，补勾不冒充准时）；今天轮到的重复类双区展示时
 *  过期区不提供勾选入口（改为指引文案，勾选只发生在今日区，wasExpired 语义唯一——
 *  同一条目同一种完成行为不再有两种统计口径）。
 *  菜单按类型分叉：单次=「改为今天」（重写有效期日回今日区、当天可勾）/「删除」；重复类=
 *  「修改」（dueDate 不参与重复类判定，「改为今天」无语义——今天轮到的直接去今日区勾完成）/「删除」。
 *  无启用开关（过期无"明天"语义，开关只剩误导）。 */
@Composable
internal fun TodoExpiredRow(
    row: TodoRow,
    onToggleDone: () -> Unit,
    onShowDetail: () -> Unit,
    onRevive: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.dueToday) {
            // 统一口径：今天轮到的过期行不设补勾入口（今日区已可勾，准时/迟到口径唯一）
            Text(
                stringResource(R.string.todos_expired_due_today_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = Spacing.xs),
            )
        } else {
            // 补勾框恒未选中态：已补完的条目当场离开过期区，这里没有「已勾的过期行」
            Checkbox(
                checked = false,
                onCheckedChange = { onToggleDone() },
                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
            )
        }
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
                // 错过的轮到日（specs/007：过期归因看"哪天失败的"；单次=有效期日、重复类=最近错过的轮到日）；
                // 010 提醒角标照常展示（配置可见性，非会响承诺）
                row.dateLabel + (
                    if (row.todo.reminderTimes.isNotEmpty()) {
                        " · " + stringResource(
                            R.string.todos_reminder_inline,
                            reminderTimesForDisplay(row.todo.reminderTimes),
                        )
                    } else {
                        ""
                    }
                    ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        RowMenu(
            hasRevive = row.todo.repeatType == TodoRecurrence.REPEAT_ONCE,
            hasRename = row.todo.repeatType != TodoRecurrence.REPEAT_ONCE,
            onRename = onRename,
            onRevive = onRevive,
            onDelete = onDelete,
        )
    }
}

/** ⋯ 下拉菜单（今日/过期两区共用骨架；contentDescription 补齐 TalkBack 可达性，2026-11）。 */
@Composable
private fun RowMenu(
    hasRevive: Boolean,
    hasRename: Boolean,
    onRename: () -> Unit,
    onRevive: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.common_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (hasRevive) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.todos_menu_revive)) },
                    onClick = {
                        menuOpen = false
                        onRevive()
                    },
                )
            }
            if (hasRename) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.todos_menu_rename)) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
            }
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
