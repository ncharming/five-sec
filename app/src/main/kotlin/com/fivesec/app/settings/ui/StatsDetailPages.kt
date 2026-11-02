package com.fivesec.app.settings.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.fivesec.app.R
import com.fivesec.app.settings.viewmodels.AppRangeStatsUi
import com.fivesec.app.settings.viewmodels.StatsPeriod
import com.fivesec.app.settings.viewmodels.StatsRange
import com.fivesec.app.settings.viewmodels.TodoRangeStatsUi
import com.fivesec.app.ui.components.AppIcon
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.theme.Spacing

/**
 * 统计两个二级页的共用组件（自 StatsScreen 拆出，2026-11）：二级页头、日/周/月/年选择行、
 * 时段分布卡、任务历史卡、应用历史卡——两个二级页共享档位/周期选择（状态在 VM）。
 */

/** 二级页页头：返回箭头 + 标题（与主页页头同视觉重量）。 */
@Composable
internal fun DetailHeader(title: String, onBack: () -> Unit) {
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
internal fun RangeSelector(
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

/** 时段分布卡（specs/011）：24 桶柱状图回答「几点最容易破防」。含全部结局事件（与拦截总数
 *  同口径）；零数据照常渲染（全零柱 + 基线 +「共 0 次」，无峰值行）；峰值柱品牌绿实色、
 *  其余 35% 透明度，峰值并列取最靠前时刻。 */
@Composable
internal fun HourDistributionCard(buckets: List<Int>, modifier: Modifier = Modifier) {
    val total = buckets.sum()
    val max = buckets.maxOrNull() ?: 0
    val peakHour = if (max > 0) buckets.indexOfFirst { it == max } else null

    CardSurface(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.stats_hour_distribution),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.stats_hour_total, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
            val peakColor = MaterialTheme.colorScheme.primary
            val baselineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(top = Spacing.md),
            ) {
                val gap = 2.dp.toPx()
                val barWidth = (size.width - gap * (buckets.size - 1)) / buckets.size
                buckets.forEachIndexed { index, count ->
                    if (count <= 0) return@forEachIndexed
                    val barHeight = size.height * count / max
                    drawRect(
                        color = if (index == peakHour) peakColor else barColor,
                        topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                        size = Size(barWidth, barHeight),
                    )
                }
                drawLine(
                    color = baselineColor,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            // 5 点轴标：两端带「时」后缀，中间纯数字
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val labelStyle = MaterialTheme.typography.labelSmall
                val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
                Text(stringResource(R.string.stats_hour_label, 0), style = labelStyle, color = labelColor)
                Text("6", style = labelStyle, color = labelColor)
                Text("12", style = labelStyle, color = labelColor)
                Text("18", style = labelStyle, color = labelColor)
                Text(stringResource(R.string.stats_hour_label, 23), style = labelStyle, color = labelColor)
            }
            if (peakHour != null) {
                Text(
                    stringResource(R.string.stats_hour_peak, peakHour, max),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}

/** 任务历史：周期总完成次数卡（0 也是有效数据，照常渲染）；过期补完 >0 时拆分行显式指出
 *  （2026-10-04：迟到完成不冒充准时——总数含补完，拆行让成分看得见）。 */
@Composable
internal fun TodoPeriodTotalCard(total: Int, lateTotal: Int) {
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
            if (lateTotal > 0) {
                Text(
                    stringResource(R.string.stats_todo_period_late, lateTotal),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.xs),
                )
            }
        }
    }
}

/** 任务历史条目卡：文本快照（单行省略）+ 完成次数（删除的条目按快照展示不失联）；
 *  含过期补完时次数旁小字标出（2026-10-04：正常完成与补完不混算功劳）。 */
@Composable
internal fun TodoRangeStatCard(ui: TodoRangeStatsUi, modifier: Modifier = Modifier) {
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
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = Spacing.md)) {
                Text(
                    stringResource(R.string.stats_todo_item_count, ui.completions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (ui.lateCompletions > 0) {
                    Text(
                        stringResource(R.string.stats_todo_item_late, ui.lateCompletions),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

internal fun rangeLabelRes(range: StatsRange): Int = when (range) {
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

/** 应用历史卡：白底 + 真实图标 + 名称 + 三列指标（统一风格，替代原彩色整卡）。
 *  底部停留行（specs/012）：周期内会话墙钟时长，分钟向上取整，无会话不显示。 */
@Composable
internal fun AppRangeStatCard(ui: AppRangeStatsUi, modifier: Modifier = Modifier) {
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
        // 停留行：会话墙钟时长（specs/012）——「拦截 12 次」之外补「停留多久」的另一半真相
        val stayMinutes = (ui.stayMillis + MILLIS_PER_MINUTE - 1) / MILLIS_PER_MINUTE
        if (stayMinutes > 0) {
            Text(
                stringResource(R.string.stats_stay_minutes, stayMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg),
            )
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

/** 停留行分钟取整单位（specs/012，与 UsageSessionTracker.MILLIS_PER_MINUTE 同值——UI 层不引 interception 包）。 */
private const val MILLIS_PER_MINUTE = 60_000L
