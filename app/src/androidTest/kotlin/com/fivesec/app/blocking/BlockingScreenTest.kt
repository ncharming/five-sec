package com.fivesec.app.blocking

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fivesec.app.blocking.ui.BlockingScreen
import com.fivesec.app.ui.theme.FiveSecTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 拦截页 UI：倒计时时按钮锁定、标题与应用名正确显示。 */
@RunWith(AndroidJUnit4::class)
class BlockingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun blocking_screen_shows_title_and_locked_buttons_during_countdown() {
        val vm = BlockingViewModel("抖音")
        composeRule.setContent { FiveSecTheme { BlockingScreen(vm) } }

        composeRule.onNodeWithText("是否打开 抖音？").assertIsDisplayed()
        // 倒计时初始为 5
        composeRule.onNodeWithText("5").assertIsDisplayed()
        // 倒计时进行中：打开/取消按钮应被禁用（强制减速带）
        composeRule.onNodeWithText("打开").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").assertIsNotEnabled()
    }
}
