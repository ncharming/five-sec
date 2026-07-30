package com.fivesec.app.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.fivesec.app.settings.viewmodels.AppTodayStatsUi
import com.fivesec.app.settings.viewmodels.StatsViewModel
import com.fivesec.app.ui.theme.Spacing
import com.fivesec.app.util.onColorForBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit, viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val appStats by viewModel.appTodayStats.collectAsStateWithLifecycle()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.stats_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) } },
        )
    }) { padding ->
        val data = ui
        if (data.total == 0 && data.streak == 0) {
            Column(Modifier.padding(padding).padding(Spacing.xl)) {
                Text(stringResource(R.string.stats_empty), style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }
        Column(Modifier.padding(padding).padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            StatCard(stringResource(R.string.stats_today_intercepted), data.total.toString())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                StatCard(stringResource(R.string.stats_today_canceled), data.canceled.toString(), Modifier.weight(1f))
                StatCard(stringResource(R.string.stats_today_opened), data.opened.toString(), Modifier.weight(1f))
            }
            StatCard(stringResource(R.string.stats_streak), "${data.streak} 天")
            if (appStats.isNotEmpty()) {
                Text(
                    stringResource(R.string.stats_app_today_section),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                appStats.forEach { s -> AppTodayStatCard(s) }
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

@Composable
private fun AppTodayStatCard(ui: AppTodayStatsUi, modifier: Modifier = Modifier) {
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
                AppMetric(stringResource(R.string.stats_today_intercepted), ui.todayInterceptions.toString(), onColor, Modifier.weight(1f))
                AppMetric(stringResource(R.string.stats_today_opened), ui.todayOpened.toString(), onColor, Modifier.weight(1f))
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
