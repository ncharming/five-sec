package com.fivesec.app.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.fivesec.app.data.repository.HintRepository
import com.fivesec.app.settings.viewmodels.HintListViewModel
import com.fivesec.app.ui.theme.Spacing

/**
 * 自定义提示语管理页（specs/004-custom-hints）：
 * 添加的提示语并入随机池，与内置提示语合并参与拦截页随机抽取。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HintListScreen(
    viewModel: HintListViewModel = hiltViewModel(),
) {
    val hints by viewModel.hints.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.hints_title)) },
            actions = {
                IconButton(onClick = { showAddDialog = true }) { Icon(Icons.Default.Add, contentDescription = null) }
            },
        )
    }) { padding ->
        if (hints.isEmpty()) {
            Column(Modifier.padding(padding).padding(Spacing.xl)) {
                Text(stringResource(R.string.hints_empty), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(hints, key = { it.id }) { hint ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            hint.text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.remove(hint.id) }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.add(text)
                        showAddDialog = false
                    },
                    enabled = text.isNotBlank(), // 空白不可添加（Repository 入口校验双保险）
                ) { Text(stringResource(R.string.hints_add_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text(stringResource(R.string.hints_dismiss)) }
            },
            title = { Text(stringResource(R.string.hints_add)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(HintRepository.MAX_HINT_LENGTH) }, // 30 字硬截断
                    placeholder = { Text(stringResource(R.string.hints_input_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        )
    }
}
