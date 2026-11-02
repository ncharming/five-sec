package com.fivesec.app.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fivesec.app.R
import com.fivesec.app.settings.viewmodels.StatsUi
import com.fivesec.app.settings.viewmodels.TodoTodayStatsUi
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.components.PageHeader
import com.fivesec.app.ui.theme.Spacing
import com.fivesec.app.util.ResistRate
import kotlin.math.roundToInt

/**
 * 统计主页（自 StatsScreen 拆出，2026-11）：「今日任务」Hero 卡 + 「今日拦截」双 Hero 卡，
 * 均整卡可点进对应二级页（入口即内容）。空态契约（specs/002 FR 口径延续）：数值为 0 是有效
 * 数据——Hero 数字、进度条（0/0 → 0%）照常渲染 0。
 */

/** 统计主页：「今日任务」Hero 卡 + 「今日拦截」双 Hero 卡，均整卡可点进二级页（入口即内容）。 */
@Composable
internal fun StatsMainPage(
    ui: StatsUi,
    todoToday: TodoTodayStatsUi,
    onOpenAppHistory: () -> Unit,
    onOpenTodoHistory: () -> Unit,
) {
    PageHeader(
        title = stringResource(R.string.stats_title),
        subtitle = stringResource(R.string.stats_subtitle),
    )

    Column(
        Modifier.padding(horizontal = Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        TodayTasksCard(stats = todoToday, onClick = onOpenTodoHistory)
        TodayInterceptCard(stats = ui, onClick = onOpenAppHistory)
    }
}

/**
 * 今日任务 Hero 卡（视觉稿主焦点）：完成/轮到大字 + 品牌绿进度条 + 百分比 + 过期警示行。
 * 全部完成（total>0 且完成=轮到）时追加「✓ 全部完成」胶囊；total=0 渲染 0/0 · 0%
 * （0 是有效数据，不做特殊空态）。整卡可点进任务完成统计二级页。
 */
@Composable
private fun TodayTasksCard(stats: TodoTodayStatsUi, onClick: () -> Unit) {
    val allDone = stats.total > 0 && stats.completed >= stats.total
    val progressFraction = if (stats.total > 0) stats.completed.toFloat() / stats.total else 0f
    val percent = if (stats.total > 0) (stats.completed * 100.0 / stats.total).roundToInt() else 0

    CardSurface(onClick = onClick) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        ) {
            DomainRow(Icons.Filled.Checklist, stringResource(R.string.stats_domain_today_tasks))
            Row(
                modifier = Modifier.padding(top = Spacing.md),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    "${stats.completed}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "/${stats.total}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
                if (allDone) {
                    Spacer(Modifier.weight(1f))
                    AllDonePill()
                }
            }
            Text(
                stringResource(R.string.stats_tasks_hero_label, stats.total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progressFraction)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Text(
                    "$percent%",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
            ExpiredRow(stats.expired, stats.lateCompleted, modifier = Modifier.padding(top = Spacing.sm))
        }
    }
}

/** 今日拦截双 Hero 卡：拦截次数（主）| 连续天数（次，同为 5 秒锻炼语义）+ 取消/打开小字；整卡可点。 */
@Composable
private fun TodayInterceptCard(stats: StatsUi, onClick: () -> Unit) {
    CardSurface(onClick = onClick) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
        ) {
            DomainRow(Icons.Filled.Shield, stringResource(R.string.stats_today_intercepted))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(top = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroCell(
                    value = "${stats.total}",
                    label = stringResource(R.string.stats_today_intercepted),
                    emphasized = true,
                    modifier = Modifier.weight(1f),
                )
                VerticalHairline()
                HeroCell(
                    value = "${stats.streak}",
                    unit = stringResource(R.string.stats_unit_day),
                    label = stringResource(R.string.stats_streak),
                    emphasized = false,
                    modifier = Modifier.weight(1f),
                )
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(top = Spacing.md),
            )
            // 抵制率（specs/011）：结果指标一等公民——取消/(取消+打开)，INTERRUPTED 不进分母
            // （未做出选择不算一次抵制机会）；分母 0（尚无选择）显示「—」而非 0%
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.sm),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.stats_resist_rate_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    ResistRate.percent(stats.canceled, stats.opened)
                        ?.let { "$it%" }
                        ?: stringResource(R.string.stats_resist_rate_none),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
            Text(
                stringResource(R.string.stats_today_outcomes, stats.canceled, stats.opened),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
            )
        }
    }
}

/** 域标题行：品牌色圆底图标 + 域名 + ›（箭头纯装饰，可点语义由整卡承载）。 */
@Composable
private fun DomainRow(icon: ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = Spacing.sm),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 双 Hero 单元：大数字（+可选小单位）+ 灰色小标签；主格 36sp / 次格 28sp 拉开主次。 */
@Composable
private fun HeroCell(
    value: String,
    label: String,
    emphasized: Boolean,
    modifier: Modifier = Modifier,
    unit: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = if (emphasized) {
                    MaterialTheme.typography.displaySmall
                } else {
                    MaterialTheme.typography.headlineMedium
                },
                fontWeight = FontWeight.Bold,
            )
            if (unit != null) {
                Text(
                    unit,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 2.dp, bottom = 3.dp),
                )
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

/** 全完成胶囊：primaryContainer 底 + 「✓ 全部完成」，只在 total>0 且完成=轮到时出现。 */
@Composable
private fun AllDonePill() {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.stats_tasks_all_done),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 过期警示行（specs/008：过期只出现在今日）：0 常规弱化；>0 红点 + 红色加粗。
 *  今日补完数 >0 时并列展示（2026-10-04：补了的看得见——过期区补勾的事件数，与过期 n 各说各话：
 *  n 是还挂着的失败存量，m 是今天已经补掉的；全补完时 n=0 走弱化色，m 行照常可见）。 */
@Composable
private fun ExpiredRow(expired: Int, lateCompleted: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (expired > 0) {
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            stringResource(R.string.stats_expired_count, expired),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (expired > 0) FontWeight.Bold else null,
            color = if (expired > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (lateCompleted > 0) {
            Text(
                " · " + stringResource(R.string.stats_late_completed_today, lateCompleted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 5.dp),
            )
        }
    }
}

/** 卡内纵向发丝线。 */
@Composable
internal fun VerticalHairline() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    )
}
