package com.fivesec.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fivesec.app.data.datastore.SettingsDataStore
import com.fivesec.app.settings.ui.AppListScreen
import com.fivesec.app.settings.ui.OnboardingScreen
import com.fivesec.app.settings.ui.SettingsScreen
import com.fivesec.app.settings.ui.StatsScreen
import com.fivesec.app.ui.theme.FiveSecTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

object Routes {
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val APP_LIST = "app_list"
    const val STATS = "stats"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { FiveSecTheme { AppRoot(settingsDataStore) } }
    }
}

@Composable
private fun AppRoot(settingsDataStore: SettingsDataStore) {
    val settings by settingsDataStore.settings.collectAsStateWithLifecycle(initialValue = null)
    val current = settings
    if (current == null) return // 首次加载，等待
    val navController = rememberNavController()
    val startRoute = if (current.onboardingCompleted) Routes.SETTINGS else Routes.ONBOARDING

    NavHost(navController = navController, startDestination = startRoute) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                settingsDataStore = settingsDataStore,
                onDone = { navController.navigate(Routes.SETTINGS) { popUpTo(0) } },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onOpenAppList = { navController.navigate(Routes.APP_LIST) },
                onOpenStats = { navController.navigate(Routes.STATS) },
            )
        }
        composable(Routes.APP_LIST) { AppListScreen(onBack = { navController.popBackStack() }) }
        composable(Routes.STATS) { StatsScreen(onBack = { navController.popBackStack() }) }
    }
}
