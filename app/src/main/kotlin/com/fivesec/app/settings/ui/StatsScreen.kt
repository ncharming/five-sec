package com.fivesec.app.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.settings.viewmodels.AppRangeStatsUi
import com.fivesec.app.settings.viewmodels.StatsPeriod
import com.fivesec.app.settings.viewmodels.StatsRange
import com.fivesec.app.settings.viewmodels.StatsUi
import com.fivesec.app.settings.viewmodels.StatsViewModel
import com.fivesec.app.settings.viewmodels.TodoRangeStatsUi
import com.fivesec.app.settings.viewmodels.TodoTodayStatsUi
import com.fivesec.app.ui.components.AppIcon
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.theme.Spacing

/**
 * 统计页（specs/008-todo-stats 双块重构；统一视觉：大标题页头 + 白卡体系）。
 *
 * 页内三态：主页（今日拦截三列卡 + 连击卡【锚点不动】+ 今日任务三列卡 + 两张入口卡）/
 * 应用拦截统计二级页（原"各应用历史数据"整块原样搬迁）/ 任务完成统计二级页（周期总完成 +
 * 按条目完成次数）。二级页不进导航图——页内状态 + BackHandler 兜底系统返回，底部 Tab 栏全程
 * 保留且切 Tab 现场不丢（HomeScreen 的 SaveableStateHolder 免费给）；两个二级页共享档位/周期
 * 选择（同一时间视角对比两块数据，状态在 VM 里）。
 *
 * 空态契约（specs/002 FR 口径延续）：数值为 0 是有效数据——今日三卡、总完成卡照常渲染 0；
 * 仅任务条目列表无行时显示空态文案（拦截侧应用卡恒渲染目标应用 0 值卡）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val todoToday by viewModel.todoToday.collectAsStateWithLifecycle()
    val appStats by viewModel.appRangeStats.collectAsStateWithLifecycle()
    val todoRangeStats by viewModel.todoRangeStats.collectAsStateWithLifecycle()
    val selectedRange by viewModel.selectedRange.collectAsStateWithLifecycle()
    val availablePeriods by viewModel.availablePeriods.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(StatsPage.MAIN) }
    // 二级页系统返回手势/返回键 = 回统计主页（不退出 App、不切 Tab）；主页不拦截
    BackHandler(enabled = page != StatsPage.MAIN) { page = StatsPage.MAIN }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                StatsPage.MAIN -> StatsMainPage(ui, todoToday, onOpenAppHistory = { page = StatsPage.APP_HISTORY }, onOpenTodoHistory = { page = StatsPage.TODO_HISTORY })

                StatsPage.APP_HISTORY -> {
                    DetailHeader(title = stringResource(R.string.stats_entry_intercept_history), onBack = { page = StatsPage.MAIN })
                    Column(
                        Modifier.padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        RangeSelector(
                            selectedRange = selectedRange,
                            availablePeriods = availablePeriods,
                            selectedPeriod = selectedPeriod,
                            onSelectRange = viewModel::selectRange,
                            onSelectPeriod = viewModel::selectPeriod,
                        )
                        appStats.forEach { s -> AppRangeStatCard(s) }
                    }
                }

                StatsPage.TODO_HISTORY -> {
                    DetailHeader(title = stringResource(R.string.stats_entry_todo_history), onBack = { page = StatsPage.MAIN })
                    Column(
                        Modifier.padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        RangeSelector(
                            selectedRange = selectedRange,
                            availablePeriods = availablePeriods,
                            selectedPeriod = selectedPeriod,
                            onSelectRange = viewModel::selectRange,
                            onSelectPeriod = viewModel::selectPeriod,
                        )
                        TodoPeriodTotalCard(todoRangeStats.sumOf { it.completions })
                        if (todoRangeStats.isEmpty()) {
                            Text(
                                stringResource(R.string.stats_todo_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.xl),
                            )
                        } else {
                            todoRangeStats.forEach { s -> TodoRangeStatCard(s) }
                        }
                    }
                }
            }
        }
    }
}

/** 统计页页内三态（rememberSaveable 存名，跨配置变更/切 Tab 保留现场）。 */
private enum class StatsPage { MAIN, APP_HISTORY, TODO_HISTORY }

/** 统计主页：今日拦截三列卡 + 连击卡（两卡零改动，锚点）+ 今日任务三列卡 + 两张入口卡。 */
@Composable
private fun StatsMainPage(
    ui: StatsUi,
    todoToday: TodoTodayStatsUi,
    onOpenAppHistory: () -> Unit,
    onOpenTodoHistory: () -> Unit,
) {
    PageHeader(
        title = stringResource(R.string.stats_title),
        subtitle = stringResource(R.string.stats_subtitle),
    )

    Column(
        Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        // ── 今日三指标：单卡三列，发丝线分隔（锚点，零改动） ──
        CardSurface {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BigMetric(stringResource(R.string.stats_today_intercepted), ui.total.toString(), Modifier.weight(1f))
                VerticalHairline()
                BigMetric(stringResource(R.string.stats_today_canceled), ui.canceled.toString(), Modifier.weight(1f))
                VerticalHairline()
                BigMetric(stringResource(R.string.stats_today_opened), ui.opened.toString(), Modifier.weight(1f))
            }
        }

        // ── 连续完成天数（锚点，零改动） ──
        CardSurface {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "${ui.streak} 天",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.stats_streak),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }

        // ── 今日任务三数（specs/008）：任务=轮到且启用（D/T 同口径）/ 完成 / 过期 ──
        CardSurface {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(vertical = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BigMetric(stringResource(R.string.stats_today_tasks), todoToday.total.toString(), Modifier.weight(1f))
                VerticalHairline()
                BigMetric(stringResource(R.string.stats_today_tasks_done), todoToday.completed.toString(), Modifier.weight(1f))
                VerticalHairline()
                BigMetric(stringResource(R.string.stats_today_tasks_expired), todoToday.expired.toString(), Modifier.weight(1f))
            }
        }

        // ── 两块入口：历史数据下沉二级页（specs/008） ──
        EntryCard(
            icon = Icons.Filled.Apps,
            title = stringResource(R.string.stats_entry_intercept_history),
            onClick = onOpenAppHistory,
        )
        EntryCard(
            icon = Icons.Filled.Checklist,
            title = stringResource(R.string.stats_entry_todo_history),
            onClick = onOpenTodoHistory,
        )
    }
}

/** 二级页页头：返回箭头 + 标题（与主页页头同视觉重量）。 */
@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Spacing.xs, end = Spacing.lg, top = Spacing.lg, bottom = Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.stats_back),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 单行入口卡：图标 + 标题 + › 箭头，整卡可点进二级页。 */
@Composable
private fun EntryCard(icon: ImageVector, title: String, onClick: () -> Unit) {
    CardSurface(onClick = onClick) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null, // 装饰性图标，标题文本已承担语义
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.md),
            )
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 日/周/月/年分段 + 周期 Chip（两个二级页共用，选择状态在 VM → 跨页共享）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(
    selectedRange: StatsRange,
    availablePeriods: List<StatsPeriod>,
    selectedPeriod: StatsPeriod,
    onSelectRange: (StatsRange) -> Unit,
    onSelectPeriod: (StatsPeriod) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        StatsRange.entries.forEachIndexed { index, range ->
            SegmentedButton(
                selected = range == selectedRange,
                onClick = { onSelectRange(range) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = StatsRange.entries.size,
                ),
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Text(stringResource(rangeLabelRes(range)))
            }
        }
    }
    if (selectedRange != StatsRange.DAY) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            availablePeriods.forEach { period ->
                FilterChip(
                    selected = period == selectedPeriod,
                    onClick = { onSelectPeriod(period) },
                    label = { Text(periodLabel(period)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }
}

/** 任务历史：周期总完成次数卡（0 也是有效数据，照常渲染）。 */
@Composable
private fun TodoPeriodTotalCard(total: Int) {
    CardSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$total 次",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(R.string.stats_todo_period_total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

/** 任务历史条目卡：文本快照（单行省略）+ 完成次数（删除的条目按快照展示不失联）。 */
@Composable
private fun TodoRangeStatCard(ui: TodoRangeStatsUi, modifier: Modifier = Modifier) {
    CardSurface(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ui.todoText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.stats_todo_item_count, ui.completions),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = Spacing.md),
            )
        }
    }
}

/** 大数字指标：加粗数值 + 灰色小标签。 */
@Composable
private fun BigMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** 卡内纵向发丝线。 */
@Composable
private fun VerticalHairline() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}

private fun rangeLabelRes(range: StatsRange): Int = when (range) {
    StatsRange.DAY -> R.string.stats_range_day
    StatsRange.WEEK -> R.string.stats_range_week
    StatsRange.MONTH -> R.string.stats_range_month
    StatsRange.YEAR -> R.string.stats_range_year
}

@Composable
private fun periodLabel(period: StatsPeriod): String = when (period.range) {
    StatsRange.WEEK -> stringResource(
        if (period.isCurrent) R.string.stats_period_current_week else R.string.stats_period_last_week,
    )
    StatsRange.MONTH -> stringResource(R.string.stats_period_month, period.month ?: 0)
    StatsRange.YEAR -> stringResource(R.string.stats_period_year, period.year ?: 0)
    StatsRange.DAY -> stringResource(R.string.stats_range_day)
}

/** 应用历史卡：白底 + 真实图标 + 名称 + 三列指标（统一风格，替代原彩色整卡）。 */
@Composable
private fun AppRangeStatCard(ui: AppRangeStatsUi, modifier: Modifier = Modifier) {
    CardSurface(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(packageName = ui.packageName, appName = ui.appName)
            Text(
                ui.appName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = Spacing.md),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            AppMetric(stringResource(R.string.stats_metric_intercepted), ui.interceptions.toString(), Modifier.weight(1f))
            AppMetric(stringResource(R.string.stats_metric_canceled), ui.canceled.toString(), Modifier.weight(1f))
            AppMetric(stringResource(R.string.stats_metric_opened), ui.opened.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun AppMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}
