package com.fivesec.app.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.data.repository.HintRepository
import com.fivesec.app.settings.viewmodels.HintListViewModel
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.FiveSecDialog
import com.fivesec.app.ui.components.FiveSecTextFieldShape
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.components.fiveSecTextFieldColors
import com.fivesec.app.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 自定义提示语管理页（specs/004-custom-hints，统一视觉）：
 * - 大标题页头 + 绿色圆形添加按钮（与拦截应用页同款）；
 * - 自定义提示语入白卡（行内 ✕ 删除，发丝线分隔）；
 * - 下方"内置提示语"只读区：strings.xml 预设文案置灰展示，仅参与随机抽取，不可增删改；
 * - 添加弹窗走统一 FiveSecDialog 外壳：开场聚焦即输、字数反馈收进 supportingText
 *   （上限沿用 MAX_HINT_LENGTH，不新增校验规则）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HintListScreen(
    viewModel: HintListViewModel = hiltViewModel(),
) {
    val hints by viewModel.hints.collectAsStateWithLifecycle()
    val builtinHints = stringArrayResource(R.array.blocking_exercise_hints)
    var showAddDialog by remember { mutableStateOf(false) }
    // 输入内容提升到页面级：FiveSecDialog 在退场动画期间仍持有内容，重开时由"+"按钮重置
    var newHintText by remember { mutableStateOf("") }
    // 弹窗展开即聚焦输入框并拉起键盘：输入型弹窗省一次点按
    // （稍等入场动画起步再请求，防窗口未就绪；最坏情况是仅聚焦，点一下仍可输入）
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(showAddDialog) {
        if (showAddDialog) {
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

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
                    onClick = {
                        newHintText = "" // 每次打开从空输入开始（维持原先 dialog 内 remember 的语义）
                        showAddDialog = true
                    },
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

    // 添加提示语弹窗：空白不可添加（Repository 入口校验双保险），30 字硬截断不变
    val confirmAdd = {
        // 退场动画期间按钮仍在屏幕上：以可见态作守卫，防重复提交
        if (showAddDialog) viewModel.add(newHintText)
        showAddDialog = false
    }
    FiveSecDialog(
        visible = showAddDialog,
        onDismissRequest = { showAddDialog = false },
        title = stringResource(R.string.hints_add),
        confirmButton = {
            Button(onClick = confirmAdd, enabled = newHintText.isNotBlank()) {
                Text(stringResource(R.string.hints_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { showAddDialog = false }) {
                Text(stringResource(R.string.hints_dismiss))
            }
        },
    ) {
        OutlinedTextField(
            value = newHintText,
            onValueChange = { newHintText = it.take(HintRepository.MAX_HINT_LENGTH) }, // 30 字硬截断
            placeholder = { Text(stringResource(R.string.hints_input_hint)) },
            singleLine = true,
            shape = FiveSecTextFieldShape,
            colors = fiveSecTextFieldColors(),
            supportingText = {
                // 字数反馈：仅展示当前长度/上限，截断与校验规则不变
                Text(
                    stringResource(R.string.hints_char_count, newHintText.length, HintRepository.MAX_HINT_LENGTH),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (newHintText.isNotBlank()) confirmAdd() }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )
    }
}
