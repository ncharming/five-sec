package com.fivesec.app.blocking

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.fivesec.app.util.DebugLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.fivesec.app.domain.model.InterceptionEvent
import com.fivesec.app.domain.model.InterceptionOutcome
import com.fivesec.app.interception.InterceptionController
import com.fivesec.app.ui.theme.FiveSecTheme
import com.fivesec.app.blocking.ui.BlockingScreen
import com.fivesec.app.data.repository.InterceptionRepository
import com.fivesec.app.util.PackageUtil
import com.fivesec.app.util.TimeProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/** 拦截页：由 AccessibilityService 命中目标应用后拉起。 */
@AndroidEntryPoint
class BlockingActivity : ComponentActivity() {

    @Inject lateinit var interceptionController: InterceptionController
    @Inject lateinit var interceptionRepository: InterceptionRepository
    @Inject lateinit var timeProvider: TimeProvider
    @Inject lateinit var appScope: kotlinx.coroutines.CoroutineScope

    private val targetPackage: String by lazy { intent?.getStringExtra(EXTRA_PACKAGE).orEmpty() }
    private val appLabel: String by lazy { PackageUtil.label(packageManager, targetPackage) }

    private val viewModel: BlockingViewModel by viewModels { BlockingViewModel.factory(appLabel) }
    @Volatile private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugLog.log(this,"[DBG-FS] BlockingActivity.onCreate targetPackage=$targetPackage")
        if (targetPackage.isEmpty()) { DebugLog.log(this,"[DBG-FS] empty targetPackage → finish"); finish(); return }
        setContent { FiveSecTheme { BlockingScreen(viewModel) } }
        DebugLog.log(this,"[DBG-FS] setContent done appLabel=$appLabel")

        lifecycleScope.launch {
            viewModel.ui.collect { state ->
                if (state is BlockingViewModel.UiState.Finished) handleFinished(state.outcome)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DebugLog.log(this,"[DBG-FS] BlockingActivity.onResume")
    }

    /** 用户离开（Home/息屏/返回）→ 视为被打断。 */
    override fun onPause() {
        super.onPause()
        DebugLog.log(this,"[DBG-FS] BlockingActivity.onPause → markInterrupted()")
        viewModel.markInterrupted()
    }

    override fun onDestroy() {
        super.onDestroy()
        DebugLog.log(this,"[DBG-FS] BlockingActivity.onDestroy")
    }

    private fun handleFinished(outcome: InterceptionOutcome) {
        DebugLog.log(this,"[DBG-FS] handleFinished outcome=$outcome alreadyHandled=$handled")
        if (handled) return
        handled = true

        val completed = outcome != InterceptionOutcome.INTERRUPTED
        appScope.launch {
            interceptionRepository.record(
                InterceptionEvent(
                    packageName = targetPackage,
                    timestamp = timeProvider.now(),
                    exerciseCompleted = completed,
                    outcome = outcome,
                )
            )
        }

        when (outcome) {
            InterceptionOutcome.OPENED -> {
                interceptionController.armSuppression(targetPackage)
                @Suppress("DEPRECATION")
                packageManager.getLaunchIntentForPackage(targetPackage)?.let {
                    startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            InterceptionOutcome.CANCELED, InterceptionOutcome.INTERRUPTED -> {
                // 已在桌面（拦截前执行了 GLOBAL_ACTION_HOME），留在桌面即可
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "target_package"

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, BlockingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra(EXTRA_PACKAGE, pkg)
            }
    }
}
