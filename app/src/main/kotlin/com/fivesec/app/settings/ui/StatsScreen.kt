package com.fivesec.app.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.roundToInt

/**
 * 统计页（specs/008 双块结构 + 2026-09 用户拍板主页视觉重构；统一视觉：大标题页头 + 白卡体系）。
 *
 * 页内三态：主页（「今日任务」Hero 卡 + 「今日拦截」双 Hero 卡，均整卡可点进对应二级页）/
 * 应用拦截统计二级页（原"各应用历史数据"整块原样搬迁）/ 任务完成统计二级页（周期总完成 +
 * 按条目完成次数）。二级页不进导航图——页内状态 + BackHandler 兜底系统返回，底部 Tab 栏全程
 * 保留且切 Tab 现场不丢（HomeScreen 的 SaveableStateHolder 免费给）；两个二级页共享档位/周期
 * 选择（同一时间视角对比两块数据，状态在 VM 里）。
 *
 * 主页重构口径（用户拍板）：一屏两域、每域一个焦点——原「拦截三列卡 + 连击卡 + 任务三列卡 +
 * 两张入口行」的等权重布局改为双 Hero 卡；连击并入拦截卡（同为 5 秒锻炼语义）；数据全部仍读
 * StatsViewModel 现有流（ui/todoToday），0 是有效数据照常渲染，跨零点沿用 VM 构造锚点。
 * 原 AGENTS.md「今日四卡」锚点属计划内重构（验证标准第 4 条的用户拍板例外）。
 *
 * 空态契约（specs/002 FR 口径延续）：数值为 0 是有效数据——Hero 数字、进度条（0/0 → 0%）照常
 * 渲染 0；仅任务条目列表无行时显示空态文案（拦截侧应用卡恒渲染目标应用 0 值卡）。
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

/** 统计主页：「今日任务」Hero 卡 + 「今日拦截」双 Hero 卡，均整卡可点进二级页（入口即内容）。 */
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
        TodayTasksCard(stats = todoToday, onClick = onOpenTodoHistory)
        TodayInterceptCard(stats = ui, onClick = onOpenAppHistory)
    }
}

/**
 * 今日任务 Hero 卡（视觉稿主焦点）：完成/轮到大字 + 品牌绿进度条 + 百分比 + 过期警示行。
 * 全部完成（total>0 且完成=轮到）时追加「✓ 全部完成」胶囊；total=0 渲染 0/0 · 0%
 * （0 是有效数据，不做特殊空态）。整卡可点进任务完成统计二级页。
 */
@Composable
private fun TodayTasksCard(stats: TodoTodayStatsUi, onClick: () -> Unit) {
    val allDone = stats.total > 0 && stats.completed >= stats.total
    val progressFraction = if (stats.total > 0) stats.completed.toFloat() / stats.total else 0f
    val percent = if (stats.total > 0) (stats.completed * 100.0 / stats.total).roundToInt() else 0

    CardSurface(onClick = onClick) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        ) {
            DomainRow(Icons.Filled.Checklist, stringResource(R.string.stats_domain_today_tasks))
            Row(
                modifier = Modifier.padding(top = Spacing.md),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "${stats.completed}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "/${stats.total}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
                if (allDone) {
                    Spacer(Modifier.weight(1f))
                    AllDonePill()
                }
            }
            Text(
                stringResource(R.string.stats_tasks_hero_label, stats.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
            ExpiredRow(stats.expired, modifier = Modifier.padding(top = Spacing.sm))
        }
    }
}

/** 今日拦截双 Hero 卡：拦截次数（主）| 连续天数（次，同为 5 秒锻炼语义）+ 取消/打开小字；整卡可点。 */
@Composable
private fun TodayInterceptCard(stats: StatsUi, onClick: () -> Unit) {
    CardSurface(onClick = onClick) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        ) {
            DomainRow(Icons.Filled.Shield, stringResource(R.string.stats_today_intercepted))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroCell(
                    value = "${stats.total}",
                    label = stringResource(R.string.stats_today_intercepted),
                    emphasized = true,
                    modifier = Modifier.weight(1f),
                )
                VerticalHairline()
                HeroCell(
                    value = "${stats.streak}",
                    unit = stringResource(R.string.stats_unit_day),
                    label = stringResource(R.string.stats_streak),
                    emphasized = false,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = Spacing.md),
            )
            Text(
                stringResource(R.string.stats_today_outcomes, stats.canceled, stats.opened),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
            )
        }
    }
}

/** 域标题行：品牌色圆底图标 + 域名 + ›（箭头纯装饰，可点语义由整卡承载）。 */
@Composable
private fun DomainRow(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.sm),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 双 Hero 单元：大数字（+可选小单位）+ 灰色小标签；主格 36sp / 次格 28sp 拉开主次。 */
@Composable
private fun HeroCell(
    value: String,
    label: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = if (emphasized) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                fontWeight = FontWeight.Bold,
            )
            if (unit != null) {
                Text(
                    unit,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** 全完成胶囊：primaryContainer 底 + 「✓ 全部完成」，只在 total>0 且完成=轮到时出现。 */
@Composable
private fun AllDonePill() {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.stats_tasks_all_done),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 过期警示行（specs/008：过期只出现在今日）：0 常规弱化；>0 红点 + 红色加粗。 */
@Composable
private fun ExpiredRow(expired: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (expired > 0) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            stringResource(R.string.stats_expired_count, expired),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (expired > 0) FontWeight.Bold else null,
            color = if (expired > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
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
