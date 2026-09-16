package com.fivesec.app.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.settings.viewmodels.AppRangeStatsUi
import com.fivesec.app.settings.viewmodels.StatsPeriod
import com.fivesec.app.settings.viewmodels.StatsRange
import com.fivesec.app.settings.viewmodels.StatsViewModel
import com.fivesec.app.ui.theme.Spacing
import com.fivesec.app.util.onColorForBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val appStats by viewModel.appRangeStats.collectAsStateWithLifecycle()
    val selectedRange by viewModel.selectedRange.collectAsStateWithLifecycle()
    val availablePeriods by viewModel.availablePeriods.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.stats_title)) },
        )
    }) { padding ->
        val data = ui
        if (data.total == 0 && data.streak == 0) {
            Column(Modifier.padding(padding).padding(Spacing.xl)) {
                Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).padding(Spacing.lg).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            StatCard(stringResource(R.string.stats_today_intercepted), data.total.toString())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                StatCard(stringResource(R.string.stats_today_canceled), data.canceled.toString(), Modifier.weight(1f))
                StatCard(stringResource(R.string.stats_today_opened), data.opened.toString(), Modifier.weight(1f))
            }
            StatCard(stringResource(R.string.stats_streak), "${data.streak} 天")
            if (appStats.isNotEmpty()) {
                Text(
                    stringResource(R.string.stats_app_history_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Spacing.sm),
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
                            )
                        }
                    }
                }
                appStats.forEach { s -> AppRangeStatCard(s) }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
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

@Composable
private fun AppRangeStatCard(ui: AppRangeStatsUi, modifier: Modifier = Modifier) {
    val bg = Color(ui.brandColorArgb)
    val onColor = Color(onColorForBackground(ui.brandColorArgb))
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = onColor),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(ui.appName, style = MaterialTheme.typography.titleMedium, color = onColor)
            Row(
                Modifier.padding(top = Spacing.sm).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                AppMetric(stringResource(R.string.stats_metric_intercepted), ui.interceptions.toString(), onColor, Modifier.weight(1f))
                AppMetric(stringResource(R.string.stats_metric_canceled), ui.canceled.toString(), onColor, Modifier.weight(1f))
                AppMetric(stringResource(R.string.stats_metric_opened), ui.opened.toString(), onColor, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AppMetric(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
