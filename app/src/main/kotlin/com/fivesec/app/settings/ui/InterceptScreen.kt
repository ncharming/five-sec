package com.fivesec.app.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.domain.model.TargetApp
import com.fivesec.app.settings.viewmodels.AppListViewModel
import com.fivesec.app.settings.viewmodels.SettingsViewModel
import com.fivesec.app.ui.components.AppIcon
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.CapacitySegments
import com.fivesec.app.ui.components.FiveSecDialog
import com.fivesec.app.ui.components.FiveSecTextFieldShape
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.components.fiveSecSwitchColors
import com.fivesec.app.ui.components.fiveSecTextFieldColors
import com.fivesec.app.ui.theme.Spacing
import com.fivesec.app.util.AccessibilityPermissionHelper
import com.fivesec.app.util.PackageUtil
import com.fivesec.app.util.SystemTimeProvider
import com.fivesec.app.util.TimeProvider

/**
 * 拦截页（specs/005-daily-todos 信息架构重构）：原「五秒」首页与「拦截源」页的合并体。
 * 自上而下：页头（+ 添加应用）→ 拦截总开关卡（含无障碍状态行）→ 名额行 → 应用卡片列表 → 名额满提示。
 *
 * 两个既有 ViewModel 并挂本页、职责不合并：[SettingsViewModel] 管 DataStore 总开关，
 * [AppListViewModel] 管目标应用清单——开关与清单语义不同，合并只会制造胖 VM。
 * 开关/无障碍状态行为与原 SettingsScreen 零改动；清单交互与原 AppListScreen 零改动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterceptScreen(
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    appListViewModel: AppListViewModel = hiltViewModel(),
    timeProvider: TimeProvider = SystemTimeProvider(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val globalEnabled = settings.globalInterceptionEnabled

    val apps by appListViewModel.targetApps.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }
    var showLimitDialog by remember { mutableStateOf(false) }
    // 搜索词提升到页面级：FiveSecDialog 在退场动画期间仍持有内容，重开时由"+"按钮重置
    var searchQuery by remember { mutableStateOf("") }
    val isFull = apps.size >= TargetAppRepository.MAX_APPS

    // 无障碍服务状态：ON_RESUME 重查（用户跳系统设置开启后返回时刷新），与原五秒页一致
    var serviceEnabled by remember { mutableStateOf(AccessibilityPermissionHelper.isServiceEnabled(context)) }
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

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // ── 页头：大标题 + 副标语 + 添加应用按钮 ──
            PageHeader(
                title = stringResource(R.string.intercept_title),
                subtitle = stringResource(R.string.intercept_subtitle),
            ) {
                FilledIconButton(
                    onClick = {
                        if (isFull) {
                            showLimitDialog = true
                        } else {
                            searchQuery = "" // 每次打开从空搜索开始（维持原先 dialog 内 remember 的语义）
                            showPicker = true
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.app_list_add))
                }
            }

            // ── 拦截总开关卡（原五秒页主体，行为零改动） ──
            CardSurface(Modifier.padding(horizontal = Spacing.lg)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.settings_global_switch),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = globalEnabled,
                        onCheckedChange = settingsViewModel::setGlobalEnabled,
                        colors = fiveSecSwitchColors(),
                    )
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                // 无障碍服务状态：已开启绿色只读；未开启点击一键开启（失败跳系统设置）
                // 注：TextButtonDefaults 已在 material3 1.3.0 移除，内容色直接由 Text.color 控制
                TextButton(
                    onClick = {
                        if (serviceEnabled) return@TextButton
                        // 已授权 WRITE_SECURE_SETTINGS 时直接一键开启，失败再跳系统设置
                        serviceEnabled = AccessibilityPermissionHelper.enableService(context)
                        if (!serviceEnabled) AccessibilityPermissionHelper.openAccessibilitySettings(context)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xs),
                ) {
                    Text(
                        if (serviceEnabled) stringResource(R.string.settings_accessibility_status_on)
                        else stringResource(R.string.settings_accessibility_status_off),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (serviceEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── 名额行：已启用数 + 进度段 + n/3 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.app_list_enabled_count, apps.count { it.isEnabled }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                CapacitySegments(filled = apps.size, total = TargetAppRepository.MAX_APPS)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    stringResource(R.string.app_list_capacity, apps.size, TargetAppRepository.MAX_APPS),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 应用卡片列表 ──
            if (apps.isEmpty()) {
                Text(
                    stringResource(R.string.app_list_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.xl),
                )
            } else {
                CardSurface(Modifier.padding(horizontal = Spacing.lg)) {
                    apps.forEachIndexed { index, app ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                        }
                        AppRow(
                            app = app,
                            onToggle = { appListViewModel.setEnabled(app.packageName, it) },
                            onRemove = { appListViewModel.remove(app.packageName) },
                        )
                    }
                }
            }

            // ── 名额已满提示（前置告知，替代纯弹窗反馈） ──
            if (isFull) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Icon(
                        Icons.Outlined.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(R.string.app_list_full_hint, TargetAppRepository.MAX_APPS),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }

    // 3个应用限制提示对话框（点 + 时兜底）
    FiveSecDialog(
        visible = showLimitDialog,
        onDismissRequest = { showLimitDialog = false },
        title = stringResource(R.string.common_notice),
        confirmButton = {
            Button(onClick = { showLimitDialog = false }) {
                Text(stringResource(R.string.common_confirm))
            }
        },
    ) {
        Text(
            stringResource(R.string.app_list_limit_reached),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ── 添加应用弹窗：搜索 + 列表直选，行点击即添加并关闭 ──
    val installed by appListViewModel.installedApps.collectAsStateWithLifecycle()
    val addedKeys = remember(apps) { apps.map { it.packageName }.toSet() }
    val filtered = remember(installed, searchQuery, addedKeys) {
        PackageUtil.filterInstalledApps(installed, addedKeys, searchQuery)
    }
    FiveSecDialog(
        visible = showPicker,
        onDismissRequest = { showPicker = false },
        title = stringResource(R.string.app_list_add),
        confirmButton = {
            Button(onClick = { showPicker = false }) {
                Text(stringResource(R.string.common_done))
            }
        },
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.app_list_search_hint)) },
            singleLine = true,
            shape = FiveSecTextFieldShape,
            colors = fiveSecTextFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (filtered.isEmpty()) {
            Text(
                stringResource(R.string.app_list_search_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.md),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(filtered, key = { it.app.packageName }) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (item.isAdded) Modifier
                                else Modifier.clickable {
                                    // 退场动画期间行仍在屏幕上：以可见态作守卫，防重复添加
                                    if (showPicker) appListViewModel.add(item.app.packageName, timeProvider.now())
                                    showPicker = false
                                },
                            )
                            .padding(vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            item.app.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (item.isAdded) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (item.isAdded) {
                            Text(
                                stringResource(R.string.app_list_already_added),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 卡片行：图标 + 名称/状态 + ⋯ 菜单（包名/移除） + 启用开关。 */
@Composable
private fun AppRow(
    app: TargetApp,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            packageName = app.packageName,
            appName = app.appName,
            dimmed = !app.isEnabled,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.md)
                .alpha(if (app.isEnabled) 1f else 0.62f),
        ) {
            Text(
                app.appName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.padding(top = Spacing.xs),
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (app.isEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
                Text(
                    stringResource(
                        if (app.isEnabled) R.string.app_list_state_on else R.string.app_list_state_off,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.app_list_package_name, app.packageName),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    onClick = {}, // 仅展示，不可点击
                    enabled = false,
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.app_list_menu_remove),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onRemove()
                    },
                )
            }
        }
        Switch(
            checked = app.isEnabled,
            onCheckedChange = onToggle,
            colors = fiveSecSwitchColors(),
        )
    }
}
