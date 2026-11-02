package com.fivesec.app.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fivesec.app.R
import com.fivesec.app.data.repository.TodoRepository
import com.fivesec.app.domain.model.TodoRule
import com.fivesec.app.reminder.TodoReminderPlanner
import com.fivesec.app.ui.components.FiveSecDialog
import com.fivesec.app.ui.components.FiveSecTextFieldShape
import com.fivesec.app.ui.components.fiveSecTextFieldColors
import com.fivesec.app.ui.theme.Spacing
import com.fivesec.app.util.TodoRecurrence
import java.time.DayOfWeek

/**
 * 新建/编辑共用表单弹窗（自 TodoScreen 拆出，2026-11）：多行输入（存真实换行 \n）+ 重复规则区
 * （specs/006 三选一；007 扩为四选一，新增「单次」）+ 提醒时刻区（specs/010 起可选；013 起多条
 * chips，上限 [TodoReminderPlanner.MAX_REMINDER_TIMES]），200 字硬截断，空白不可确认。
 * 规则区：每天 / 每周几（多选周几，全空禁存并提示）/ 每N天（越界即时收敛显示 2..365，
 * 下方展示下次执行日——锚点=最近完成日，从未完成=今天）/ 单次（原「仅今天」，只在有效期日
 * 当天轮到，跨日未完成进过期分类）。分段按钮不显示选中 ✓（四段等宽排版 + 选中态已由底色表达）。
 * 弹窗内存态保留用户切走的规则勾选（体验细节），保存只落当前选中规则、其余两列归零（见 data-model 不变式）。
 * 提醒区（013）：时刻 chips 点击改（TimePicker 24h、编辑态可「移除」）、＋ 添加；切到「单次」且无时刻
 * 时预填默认 20:00/21:00/22:00 三时刻（TodoReminderPlanner.timesOnRuleSwitch 纯逻辑，原封未动才随
 * 切走还原）；保存时与初始值不同才发定向 UPDATE（与规则区同口径）。
 *
 * 状态重置口径（2026-11 修复闪白）：rememberSaveable 以 [sessionKey]（调用方每次打开自增）为重置键
 * ——**重开时重置、退场动画期间保持**。此前以 visible 为键：确认关闭的瞬间 visible=false 即重置回
 * 初始值，而 FiveSecDialog 退场动画 160ms 内内容仍在屏上，观感是「关闭瞬间表单闪回空白」。
 *
 * 习惯模板区（specs/019，[showTemplates] 仅新建弹窗开启）：静态 string-array chips 点击**整体替换**
 * 当前草稿（模板是起点不是追加）；编辑既有待办不显示——模板只服务冷启动填内容，规则与提醒仍走默认。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun TodoEditDialog(
    visible: Boolean,
    sessionKey: Int,
    title: String,
    initialText: String,
    initialRule: TodoRule,
    initialReminderTimes: String,
    today: String,
    onDismiss: () -> Unit,
    onConfirm: (String, TodoRule, String) -> Unit,
    showTemplates: Boolean = false, // 新建 true：输入框上方显示习惯模板 chips（specs/019）
    lastCompletedDate: String = "", // 新建传缺省（从未完成）；编辑传最近完成日（下次执行日的锚点）
) {
    // rememberSaveable 的键须可存 Bundle：initialRule 是数据类，以其 toString（三个 Int 字段、
    // 确定性）作 key 参与变化检测，而非对象本身；sessionKey 由调用方每次打开自增（见类 KDoc）
    val ruleKey = initialRule.toString()
    var text by rememberSaveable(sessionKey, initialText, ruleKey) { mutableStateOf(initialText) }
    var ruleType by rememberSaveable(sessionKey, initialText, ruleKey) { mutableStateOf(initialRule.repeatType) }
    var repeatDays by rememberSaveable(sessionKey, initialText, ruleKey) { mutableStateOf(initialRule.repeatDays) }
    var intervalText by rememberSaveable(sessionKey, initialText, ruleKey) {
        mutableStateOf(
            initialRule.intervalDays
                .takeIf { initialRule.repeatType == TodoRecurrence.REPEAT_INTERVAL }
                ?.toString()
                ?: "3",
        )
    }
    var reminderTimes by rememberSaveable(sessionKey, initialText, ruleKey, initialReminderTimes) {
        mutableStateOf(initialReminderTimes)
    }

    // 013：当前时刻串是否「切单次时自动预填且原封未动」——离开单次时据此还原为空（防默认值泄给重复类）
    var reminderTimesAutoFilled by rememberSaveable(sessionKey, initialText, ruleKey, initialReminderTimes) {
        mutableStateOf(false)
    }

    // TimePicker 目标槽位：TIME_SLOT_CLOSED=关、TIME_SLOT_ADD=新增、≥0=编辑该序号时刻（编辑态多一个「移除」）
    var timePickerSlot by remember { mutableStateOf(TIME_SLOT_CLOSED) }

    /** 规则段切换：时刻联动（预填/还原）先于规则赋值——联动逻辑全部在 timesOnRuleSwitch 纯函数里。 */
    fun selectRuleType(type: Int) {
        if (type == ruleType) return
        val switched = TodoReminderPlanner.timesOnRuleSwitch(ruleType, type, reminderTimes, reminderTimesAutoFilled)
        reminderTimes = switched.first
        reminderTimesAutoFilled = switched.second
        ruleType = type
    }

    /** 落定一次 TimePicker 选择：改槽/新增统一走归一化（去重升序），用户动手即摘掉 autoFilled 标记。 */
    fun applyPickedTime(slot: Int, hhmm: String) {
        val current = TodoReminderPlanner.parseReminderTimes(reminderTimes).toMutableList()
        if (slot == TIME_SLOT_ADD) current += hhmm else current[slot] = hhmm
        reminderTimes = TodoReminderPlanner.normalizeReminderTimes(current.joinToString(","))
        reminderTimesAutoFilled = false
    }

    /** 移除一个时刻：摘 autoFilled；移空即回「未设置」态。 */
    fun removeTimeAt(slot: Int) {
        val current = TodoReminderPlanner.parseReminderTimes(reminderTimes).toMutableList()
        if (slot in current.indices) {
            current.removeAt(slot)
            reminderTimes = current.joinToString(",")
            reminderTimesAutoFilled = false
        }
    }

    val weeklyIncomplete = ruleType == TodoRecurrence.REPEAT_WEEKLY && repeatDays == 0
    val intervalDays = (intervalText.toIntOrNull() ?: TodoRecurrence.MIN_INTERVAL_DAYS)
        .coerceIn(TodoRecurrence.MIN_INTERVAL_DAYS, TodoRecurrence.MAX_INTERVAL_DAYS)

    // 时刻列表（派生只读视图：解析即升序去重）——提醒区 chips 与底部 TimePicker 槽位共用
    val times = TodoReminderPlanner.parseReminderTimes(reminderTimes)

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
                    onConfirm(text.trim(), rule, reminderTimes)
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
        // 习惯模板 chips（specs/019）：仅新建弹窗——点击整体替换草稿，随后的字数计数/规则照常
        if (showTemplates) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                stringArrayResource(R.array.todo_templates).forEach { template ->
                    AssistChip(
                        onClick = { text = template },
                        label = { Text(template, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        }
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
                onClick = { selectRuleType(TodoRecurrence.REPEAT_DAILY) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 4),
                icon = {}, // 选中态由底色表达，不再画 ✓——省宽度，四段文案不再换行重叠
            ) {
                Text(stringResource(R.string.todos_rule_daily))
            }
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_WEEKLY,
                onClick = { selectRuleType(TodoRecurrence.REPEAT_WEEKLY) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 4),
                icon = {},
            ) {
                Text(stringResource(R.string.todos_rule_weekly))
            }
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_INTERVAL,
                onClick = { selectRuleType(TodoRecurrence.REPEAT_INTERVAL) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 4),
                icon = {},
            ) {
                Text(stringResource(R.string.todos_rule_interval))
            }
            SegmentedButton(
                selected = ruleType == TodoRecurrence.REPEAT_ONCE,
                onClick = { selectRuleType(TodoRecurrence.REPEAT_ONCE) },
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

        // ── 提醒时刻区（specs/010；013 起多条 chips）：点 chip 改时刻、＋ 添加、空态点击开选 ──
        Text(
            stringResource(R.string.todos_reminder_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (times.isEmpty()) {
                Text(
                    stringResource(R.string.todos_reminder_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { timePickerSlot = TIME_SLOT_ADD },
                )
            } else {
                times.forEachIndexed { index, time ->
                    InputChip(
                        selected = false,
                        onClick = { timePickerSlot = index },
                        label = { Text(time) },
                    )
                }
                AssistChip(
                    onClick = { timePickerSlot = TIME_SLOT_ADD },
                    label = { Text(stringResource(R.string.todos_reminder_add)) },
                    enabled = times.size < TodoReminderPlanner.MAX_REMINDER_TIMES, // 满上限即藏入口，下方提示上限
                )
            }
        }
        if (times.size >= TodoReminderPlanner.MAX_REMINDER_TIMES) {
            Text(
                stringResource(R.string.todos_reminder_max, TodoReminderPlanner.MAX_REMINDER_TIMES),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        if (ruleType == TodoRecurrence.REPEAT_ONCE && times.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.todos_reminder_once_hint,
                    TodoReminderPlanner.DEFAULT_ONCE_REMINDER_TIMES.joinToString("、"),
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }

    // 提醒时刻选择器（specs/010；013 起按槽位复用）：统一弹窗外壳包 Material3 TimePicker
    // （24 小时制、分钟精度）；确认回写 HH:mm（秒恒 00 不暴露）经归一化落 chips；编辑既有时刻时
    // 多一个「移除」入口（destructive 放次按钮区最左）；取消不动
    if (timePickerSlot != TIME_SLOT_CLOSED) {
        val editing = timePickerSlot >= 0
        val initial = (if (editing) times.getOrNull(timePickerSlot) else null)?.split(":")
        val pickerState = rememberTimePickerState(
            initialHour = initial?.getOrNull(0)?.toIntOrNull() ?: DEFAULT_REMINDER_HOUR,
            initialMinute = initial?.getOrNull(1)?.toIntOrNull() ?: 0,
            is24Hour = true,
        )
        FiveSecDialog(
            visible = true,
            onDismissRequest = { timePickerSlot = TIME_SLOT_CLOSED },
            title = stringResource(R.string.todos_reminder_pick_title),
            confirmButton = {
                Button(onClick = {
                    applyPickedTime(timePickerSlot, "%02d:%02d".format(pickerState.hour, pickerState.minute))
                    timePickerSlot = TIME_SLOT_CLOSED
                }) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                if (editing) {
                    TextButton(onClick = {
                        removeTimeAt(timePickerSlot)
                        timePickerSlot = TIME_SLOT_CLOSED
                    }) {
                        Text(
                            stringResource(R.string.todos_reminder_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { timePickerSlot = TIME_SLOT_CLOSED }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            TimePicker(state = pickerState)
        }
    }
}

/** TimePicker 缺省初始小时（specs/010）：晚间自律场景的常见提醒档位，纯 UX 缺省无语义。 */
private const val DEFAULT_REMINDER_HOUR = 20

/** 提醒时刻选择器的目标槽位（013）：-2=关闭；-1=新增（无「移除」）；≥0=编辑该序号时刻。 */
private const val TIME_SLOT_CLOSED = -2
private const val TIME_SLOT_ADD = -1
