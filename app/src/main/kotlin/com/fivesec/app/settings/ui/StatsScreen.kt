package com.fivesec.app.settings.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.settings.viewmodels.StatsViewModel
import com.fivesec.app.ui.theme.Spacing

/**
 * 统计页骨架（specs/008 双块结构 + 2026-09 主页视觉重构；2026-11 拆分：主页与二级页组件
 * 分别在 [StatsMainPage.kt]/[StatsDetailPages.kt]，本文件只留三态路由）。
 *
 * 页内三态：主页（「今日任务」Hero 卡 + 「今日拦截」双 Hero 卡，均整卡可点进对应二级页）/
 * 应用拦截统计二级页（原"各应用历史数据"整块原样搬迁）/ 任务完成统计二级页（周期总完成 +
 * 按条目完成次数）。二级页不进导航图——页内状态 + BackHandler 兜底系统返回，底部 Tab 栏全程
 * 保留且切 Tab 现场不丢（HomeScreen 的 SaveableStateHolder 免费给）；两个二级页共享档位/周期
 * 选择（同一时间视角对比两块数据，状态在 VM 里）。
 *
 * 主页重构口径（用户拍板）：一屏两域、每域一个焦点——连击并入拦截卡（同为 5 秒锻炼语义）；
 * 数据全部仍读 StatsViewModel 现有流（ui/todoToday），0 是有效数据照常渲染。今日锚点随零点
 * 滚动（VM 的 todayKey，2026-11）。原 AGENTS.md「今日四卡」锚点属计划内重构（验证标准第 4
 * 条的用户拍板例外）。
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val todoToday by viewModel.todoToday.collectAsStateWithLifecycle()
    val review by viewModel.reviewStats.collectAsStateWithLifecycle()
    val appStats by viewModel.appRangeStats.collectAsStateWithLifecycle()
    val todoRangeStats by viewModel.todoRangeStats.collectAsStateWithLifecycle()
    val hourDistribution by viewModel.hourDistribution.collectAsStateWithLifecycle()
    val selectedRange by viewModel.selectedRange.collectAsStateWithLifecycle()
    val availablePeriods by viewModel.availablePeriods.collectAsStateWithLifecycle()
    val selectedPeriod by viewModel.selectedPeriod.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(StatsPage.MAIN) }
    // 二级页系统返回手势/返回键 = 回统计主页（不退出 App、不切 Tab）；主页不拦截
    BackHandler(enabled = page != StatsPage.MAIN) { page = StatsPage.MAIN }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                StatsPage.MAIN -> StatsMainPage(
                    ui,
                    todoToday,
                    review,
                    onSelectReviewRange = viewModel::setReviewRange,
                    onOpenAppHistory = { page = StatsPage.APP_HISTORY },
                    onOpenTodoHistory = { page = StatsPage.TODO_HISTORY },
                )

                StatsPage.APP_HISTORY -> {
                    DetailHeader(title = stringResource(R.string.stats_entry_intercept_history), onBack = { page = StatsPage.MAIN })
                    Column(
                        Modifier.padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        RangeSelector(
                            selectedRange = selectedRange,
                            availablePeriods = availablePeriods,
                            selectedPeriod = selectedPeriod,
                            onSelectRange = viewModel::selectRange,
                            onSelectPeriod = viewModel::selectPeriod,
                        )
                        HourDistributionCard(buckets = hourDistribution)
                        appStats.forEach { s -> AppRangeStatCard(s) }
                    }
                }

                StatsPage.TODO_HISTORY -> {
                    DetailHeader(title = stringResource(R.string.stats_entry_todo_history), onBack = { page = StatsPage.MAIN })
                    Column(
                        Modifier.padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        RangeSelector(
                            selectedRange = selectedRange,
                            availablePeriods = availablePeriods,
                            selectedPeriod = selectedPeriod,
                            onSelectRange = viewModel::selectRange,
                            onSelectPeriod = viewModel::selectPeriod,
                        )
                        TodoPeriodTotalCard(
                            total = todoRangeStats.sumOf { it.completions },
                            lateTotal = todoRangeStats.sumOf { it.lateCompletions },
                        )
                        if (todoRangeStats.isEmpty()) {
                            Text(
                                stringResource(R.string.stats_todo_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = Spacing.xl),
                            )
                        } else {
                            todoRangeStats.forEach { s -> TodoRangeStatCard(s) }
                        }
                    }
                }
            }
        }
    }
}

/** 统计页页内三态（rememberSaveable 存名，跨配置变更/切 Tab 保留现场）。 */
private enum class StatsPage { MAIN, APP_HISTORY, TODO_HISTORY }
