package com.fivesec.app.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.data.repository.HintRepository
import com.fivesec.app.settings.viewmodels.HintListViewModel
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.theme.Spacing

/**
 * 自定义提示语管理页（specs/004-custom-hints，统一视觉）：
 * - 大标题页头 + 绿色圆形添加按钮（与拦截应用页同款）；
 * - 自定义提示语入白卡（行内 ✕ 删除，发丝线分隔）；
 * - 下方"内置提示语"只读区：strings.xml 预设文案置灰展示，仅参与随机抽取，不可增删改。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HintListScreen(
    viewModel: HintListViewModel = hiltViewModel(),
) {
    val hints by viewModel.hints.collectAsStateWithLifecycle()
    val builtinHints = stringArrayResource(R.array.blocking_exercise_hints)
    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            PageHeader(
                title = stringResource(R.string.hints_title),
                subtitle = stringResource(R.string.hints_subtitle),
            ) {
                FilledIconButton(
                    onClick = { showAddDialog = true },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.hints_add))
                }
            }

            Column(Modifier.padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl)) {
                if (hints.isEmpty()) {
                    Text(
                        stringResource(R.string.hints_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = Spacing.md),
                    )
                } else {
                    CardSurface {
                        hints.forEachIndexed { index, hint ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
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

                // ── 内置提示语只读区：预设文案置灰呈现，与上方可操作项区分 ──
                Text(
                    stringResource(R.string.hints_builtin_section),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = Spacing.lg, bottom = Spacing.xs),
                )
                builtinHints.forEach { text ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyLarge,
                        // M3 禁用态内容色约定：onSurface 38% 不透明度
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = Spacing.sm),
                    )
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
