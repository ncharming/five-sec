package com.fivesec.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fivesec.app.data.datastore.SettingsDataStore
import com.fivesec.app.domain.model.AppSettings
import com.fivesec.app.settings.ui.HomeScreen
import com.fivesec.app.settings.ui.OnboardingScreen
import com.fivesec.app.ui.theme.FiveSecTheme
import com.fivesec.app.util.AccessibilityPermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
}

/**
 * 应用唯一 Activity（Compose 宿主）。
 *
 * 状态栏接管（enableEdgeToEdge）：此前窗口未声明边到边，状态栏底色交由 ROM/框架主题兜底——
 * 部分设备渲染为灰底/黑条，观感像"状态栏被一层虚化遮住"。显式开启后状态栏透明、
 * 图标深浅随系统明暗切换，状态栏背后透出的就是应用自身浅色背景；
 * 正文起始位置由各页 Scaffold 按 WindowInsets 实时内缩（刘海/挖孔机型自适应，无写死高度）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { FiveSecTheme { AppRoot(settingsDataStore) } }
    }

    override fun onResume() {
        super.onResume()
        // 授权过 WRITE_SECURE_SETTINGS 后，打开应用即自动恢复被系统后台清理关闭的无障碍服务
        AccessibilityPermissionHelper.autoEnableIfPermitted(this)
    }
}

@Composable
private fun AppRoot(settingsDataStore: SettingsDataStore) {
    val settings by produceState<AppSettings?>(initialValue = null) {
        settingsDataStore.settings.collect { value = it }
    }
    val current = settings
    if (current == null) return // 首次加载，等待
    val navController = rememberNavController()
    val startRoute = if (current.onboardingCompleted) Routes.HOME else Routes.ONBOARDING

    NavHost(navController = navController, startDestination = startRoute) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                settingsDataStore = settingsDataStore,
                onDone = { navController.navigate(Routes.HOME) { popUpTo(0) } },
            )
        }
        composable(Routes.HOME) { HomeScreen() }
    }
}
