package com.fivesec.app.settings.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.settings.viewmodels.SettingsViewModel
import com.fivesec.app.util.AccessibilityPermissionHelper

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAppList: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val enabled = settings?.globalInterceptionEnabled ?: true

    var serviceEnabled by remember {
        mutableStateOf(AccessibilityPermissionHelper.isServiceEnabled(context))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = AccessibilityPermissionHelper.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.settings_global_switch), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = viewModel::setGlobalEnabled)
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            TextButton(
                onClick = { if (!serviceEnabled) AccessibilityPermissionHelper.openAccessibilitySettings(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (serviceEnabled) stringResource(R.string.settings_accessibility_status_on)
                    else stringResource(R.string.settings_accessibility_status_off),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            TextButton(onClick = onOpenAppList, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_app_list), style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(onClick = onOpenStats, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_stats), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
