package com.fivesec.app.settings.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.settings.viewmodels.AppRangeStatsUi
import com.fivesec.app.settings.viewmodels.StatsPeriod
import com.fivesec.app.settings.viewmodels.StatsRange
import com.fivesec.app.settings.viewmodels.StatsViewModel
import com.fivesec.app.ui.components.AppIcon
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.theme.Spacing

/**
 * 统计页（统一视觉：大标题页头 + 白卡体系）。
 * 今日三指标合并为单卡三列（发丝线分隔）；各应用卡改白底 + 真实图标 + 三列指标，
 * 替换原彩色整卡；日/周/月/年分段与周期 Chip 选中态统一品牌绿。
 * 数据口径与状态逻辑零改动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val appStats by viewModel.appRangeStats.collectAsStateWithLifecycle()
    val selectedRange by viewModel.selectedRange.collectAsStateWithLifecycle()
    val availablePeriods by viewModel.availablePeriods.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()

    Scaffold { padding ->
        val data = ui
        if (data.total == 0 && data.streak == 0) {
            Column(Modifier.padding(padding).padding(Spacing.xl)) {
                Text(
                    stringResource(R.string.stats_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()),
        ) {
            PageHeader(
                title = stringResource(R.string.stats_title),
                subtitle = stringResource(R.string.stats_subtitle),
            )

            Column(
                Modifier.padding(horizontal = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // ── 今日三指标：单卡三列，发丝线分隔 ──
                CardSurface {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(vertical = Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BigMetric(stringResource(R.string.stats_today_intercepted), data.total.toString(), Modifier.weight(1f))
                        VerticalHairline()
                        BigMetric(stringResource(R.string.stats_today_canceled), data.canceled.toString(), Modifier.weight(1f))
                        VerticalHairline()
                        BigMetric(stringResource(R.string.stats_today_opened), data.opened.toString(), Modifier.weight(1f))
                    }
                }

                // ── 连续完成天数 ──
                CardSurface {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(Spacing.lg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            "${data.streak} 天",
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

                // ── 各应用历史数据 ──
                if (appStats.isNotEmpty()) {
                    Text(
                        stringResource(R.string.stats_app_history_section),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
                    )
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        StatsRange.entries.forEachIndexed { index, range ->
                            SegmentedButton(
                                selected = range == selectedRange,
                                onClick = { viewModel.selectRange(range) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = StatsRange.entries.size,
                                ),
                                colors = SegmentedButtonDefaults.itemColors(
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
                                    onClick = { viewModel.selectPeriod(period) },
                                    label = { Text(periodLabel(period)) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                )
                            }
                        }
                    }
                    appStats.forEach { s -> AppRangeStatCard(s) }
                }
            }
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
