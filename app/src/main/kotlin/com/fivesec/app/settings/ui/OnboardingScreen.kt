package com.fivesec.app.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fivesec.app.R
import com.fivesec.app.data.datastore.SettingsDataStore
import com.fivesec.app.util.AccessibilityPermissionHelper
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(settingsDataStore: SettingsDataStore, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var serviceEnabled by remember {
        mutableStateOf(AccessibilityPermissionHelper.isServiceEnabled(context))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = AccessibilityPermissionHelper.isServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.onboarding_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.onboarding_restricted_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))

            if (serviceEnabled) {
                Text(stringResource(R.string.onboarding_accessibility_enabled))
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    scope.launch {
                        settingsDataStore.setOnboardingCompleted(true)
                        onDone()
                    }
                }) { Text(stringResource(R.string.onboarding_finish)) }
            } else {
                OutlinedButton(onClick = { AccessibilityPermissionHelper.openAccessibilitySettings(context) }) {
                    Text(stringResource(R.string.onboarding_enable_accessibility))
                }
            }
        }
    }
}
