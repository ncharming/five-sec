package com.fivesec.app.settings.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.fivesec.app.R
import com.fivesec.app.ui.components.CardSurface
import com.fivesec.app.ui.theme.Spacing

/**
 * 提醒权限横幅（specs/010 决策 16，自 TodoScreen 拆出 2026-11）：有已设提醒的待办才出现，
 * 逐缺失项独立一行；出现/消失由 ON_RESUME 重查驱动，不跟踪一次性动作结果。
 */

/** 提醒权限缺口快照（specs/010 横幅判定源）：三项各自独立（ReminderPermissions 同口径）。 */
internal data class ReminderPermGaps(
    val notifications: Boolean = false,
    val battery: Boolean = false,
    val fullScreen: Boolean = false,
)

/** 提醒权限横幅行：说明文案 + 「去开启」。 */
@Composable
internal fun ReminderPermBanner(text: String, onAction: () -> Unit) {
    CardSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAction) {
                Text(stringResource(R.string.todo_reminder_banner_action))
            }
        }
    }
}
