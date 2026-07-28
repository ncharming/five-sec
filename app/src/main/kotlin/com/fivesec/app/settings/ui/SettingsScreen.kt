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
import com.fivesec.app.util.DebugLog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenAppList: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val enabled = settings.globalInterceptionEnabled

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

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // 临时调试：复制/清空 FSDBG 日志（无 USB 时从手机导出）。确认根因后删除。
            TextButton(
                onClick = {
                    val text = DebugLog.read(context)
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("fivesec_debug", text))
                    Toast.makeText(context, "已复制调试日志（${text.length} 字符）", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("📋 复制调试日志（FSDBG）", style = MaterialTheme.typography.bodyLarge)
            }
            TextButton(
                onClick = {
                    DebugLog.clear(context)
                    Toast.makeText(context, "已清空调试日志", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("🗑 清空调试日志", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
