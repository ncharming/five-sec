package com.fivesec.app.settings.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Settings
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
 * 首页骨架（specs/009 起 3 Tab：待办 / 统计 / 设置，默认落待办——提示语 Tab 随功能退役移除）：
 * - 原「拦截」Tab 更名「设置」并右移到最右（内容仍是拦截总开关+目标应用管理，重构另立 spec），
 *   图标换齿轮以贴「设置」语义；选中态品牌绿、未选中灰，无胶囊指示器；点击瞬时切换，无切换动画。
 * - SaveableStateHolder 按页保持滚动位置等 UI 状态；各页 ViewModel 挂在首页
 *   NavBackStackEntry 上，切 Tab 不销毁（统计页首次切入才创建并开始加载数据）。
 * - 本层统一消费系统栏 insets（consumeWindowInsets）：四个子页面各自持有 Scaffold，
 *   边到边下状态栏/导航栏高度若内外叠加会出现双重留白。
 * - Tab 不进返回栈：任意 Tab 按返回键直接退出应用，与微信一致。
 * - 演进：原「五秒」Tab 的总开关卡已并入「设置」页顶部（InterceptScreen），
 *   腾出的首页位给「待办」（TodoScreen）。本文件虽居 settings/ui 目录，声明为根包（历史布局）。
 */
private enum class HomeTab(
    @StringRes val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
) {
    TODOS(R.string.tab_todos, Icons.Filled.Checklist, Icons.Outlined.Checklist),
    STATS(R.string.tab_stats, Icons.Filled.BarChart, Icons.Outlined.BarChart),
    SETTINGS(R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
}

@Composable
fun HomeScreen() {
    // 以枚举名持久化选中 Tab，恢复按名查找并兜底 TODOS：升级窗口的旧存档（如已退役的 TIPS）
    // 不会让枚举解析抛异常（specs/009 Tab 收窄的防崩兜底）
    var selectedTabName by rememberSaveable { mutableStateOf(HomeTab.TODOS.name) }
    val selectedTab = HomeTab.entries.find { it.name == selectedTabName } ?: HomeTab.TODOS
    val stateHolder = rememberSaveableStateHolder()

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.entries.forEach { tab ->
                    val selected = tab == selectedTab
                    NavigationBarItem(
                        selected = selected,
                        onClick = { selectedTabName = tab.name },
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
            // consumeWindowInsets：系统栏 insets 已由本层 Scaffold 吃掉，内层各页自己的
            // Scaffold 不再叠加一次状态栏/导航栏高度（边到边开启后 insets 为真实值，必须防双计）
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                when (selectedTab) {
                    HomeTab.TODOS -> TodoScreen()
                    HomeTab.STATS -> StatsScreen()
                    HomeTab.SETTINGS -> InterceptScreen()
                }
            }
        }
    }
}
