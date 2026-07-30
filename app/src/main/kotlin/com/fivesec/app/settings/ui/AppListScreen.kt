package com.fivesec.app.settings.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.settings.viewmodels.AppListViewModel
import com.fivesec.app.ui.theme.Spacing
import com.fivesec.app.util.SystemTimeProvider
import com.fivesec.app.util.TimeProvider

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    onBack: () -> Unit,
    viewModel: AppListViewModel = hiltViewModel(),
    timeProvider: TimeProvider = SystemTimeProvider(),
) {
    val apps by viewModel.targetApps.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    var expandedApp by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.app_list_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = null) }
            },
            actions = {
                IconButton(onClick = {
                    // 检查是否达到3个应用限制
                    if (apps.size >= 3) {
                        showLimitDialog = true
                    } else {
                        showPicker = true
                    }
                }) { Icon(Icons.Default.Add, contentDescription = null) }
            },
        )
    }) { padding ->
        if (apps.isEmpty()) {
            Column(Modifier.padding(padding).padding(Spacing.xl)) {
                Text(stringResource(R.string.app_list_empty), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(apps, key = { it.packageName }) { app ->
                    val isExpanded = expandedApp == app.packageName
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { expandedApp = if (isExpanded) null else app.packageName }
                                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.appName, style = MaterialTheme.typography.bodyLarge)
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "展开",
                                        modifier = Modifier.padding(start = Spacing.xs)
                                    )
                                }
                                if (app.isDefault) {
                                    Text(stringResource(R.string.app_list_default_badge), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Switch(
                                checked = app.isEnabled,
                                onCheckedChange = { viewModel.setEnabled(app.packageName, it) },
                            )
                            IconButton(onClick = { viewModel.remove(app.packageName) }) {
                                Icon(Icons.Default.Close, contentDescription = null)
                            }
                        }
                        if (isExpanded) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.lg))
                            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)) {
                                Text(
                                    stringResource(R.string.app_list_package_name, app.packageName),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // 3个应用限制提示对话框
    if (showLimitDialog) {
        AlertDialog(
            onDismissRequest = { showLimitDialog = false },
            confirmButton = {
                TextButton(onClick = { showLimitDialog = false }) {
                    Text("确定")
                }
            },
            title = { Text("提示") },
            text = { Text(stringResource(R.string.app_list_limit_reached)) },
        )
    }

    if (showPicker) {
        val installed = remember { viewModel.installedApps() }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("完成") } },
            title = { Text(stringResource(R.string.app_list_add)) },
            text = {
                LazyColumn {
                    items(installed, key = { it.packageName }) { candidate ->
                        if (candidate.packageName !in apps.map { it.packageName }) {
                            TextButton(
                                onClick = {
                                    viewModel.add(candidate.packageName, timeProvider.now())
                                    showPicker = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(candidate.label) }
                        }
                    }
                }
            },
        )
    }
}
