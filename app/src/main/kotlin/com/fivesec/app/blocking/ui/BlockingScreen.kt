package com.fivesec.app.blocking.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fivesec.app.R
import com.fivesec.app.blocking.BlockingViewModel

@Composable
fun BlockingScreen(viewModel: BlockingViewModel) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val unlocked = state is BlockingViewModel.UiState.ChoiceUnlocked ||
        state is BlockingViewModel.UiState.Finished

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.blocking_title, viewModel.appLabel),
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.blocking_exercise_hint),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))

            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(96.dp)) {
                Text(
                    text = countdownText(state, unlocked),
                    fontSize = 72.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(24.dp))
            Text(
                text = if (unlocked) "" else stringResource(R.string.blocking_wait),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(
                    onClick = viewModel::cancel,
                    enabled = unlocked,
                    modifier = Modifier.height(56.dp),
                ) { Text(stringResource(R.string.blocking_cancel)) }
                Button(
                    onClick = viewModel::open,
                    enabled = unlocked,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier.height(56.dp),
                ) { Text(stringResource(R.string.blocking_open)) }
            }
        }
    }
}

private fun countdownText(state: BlockingViewModel.UiState, unlocked: Boolean): String =
    when {
        unlocked -> "✓"
        state is BlockingViewModel.UiState.CountingDown -> state.remaining.coerceAtLeast(0).toString()
        else -> "5"
    }
