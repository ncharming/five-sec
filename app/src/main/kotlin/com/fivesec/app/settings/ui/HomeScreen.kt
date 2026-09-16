package com.fivesec.app.settings.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.fivesec.app.R

/**
 * 仿微信首页骨架：底部 4 Tab（五秒 / 拦截app / tips / 统计）。
 * - 选中态品牌绿、未选中灰，无胶囊指示器；点击瞬时切换，无切换动画。
 * - SaveableStateHolder 按页保持滚动位置等 UI 状态；各页 ViewModel 挂在首页
 *   NavBackStackEntry 上，切 Tab 不销毁（统计页首次切入才创建并开始加载数据）。
 * - Tab 不进返回栈：任意 Tab 按返回键直接退出应用，与微信一致。
 */
private enum class HomeTab(
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    FIVE_SEC(R.string.tab_fivesec, Icons.Filled.Timer, Icons.Outlined.Timer),
    APP_LIST(R.string.tab_app_list, Icons.Filled.Apps, Icons.Outlined.Apps),
    TIPS(R.string.tab_tips, Icons.Filled.Lightbulb, Icons.Outlined.Lightbulb),
    STATS(R.string.tab_stats, Icons.Filled.BarChart, Icons.Outlined.BarChart),
}

@Composable
fun HomeScreen() {
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.FIVE_SEC) }
    val stateHolder = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(
                                if (selected) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = Color.Transparent,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        stateHolder.SaveableStateProvider(selectedTab.name) {
            Box(Modifier.padding(padding)) {
                when (selectedTab) {
                    HomeTab.FIVE_SEC -> SettingsScreen()
                    HomeTab.APP_LIST -> AppListScreen()
                    HomeTab.TIPS -> HintListScreen()
                    HomeTab.STATS -> StatsScreen()
                }
            }
        }
    }
}
