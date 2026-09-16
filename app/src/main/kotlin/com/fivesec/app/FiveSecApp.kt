package com.fivesec.app

import android.app.Application
import android.util.Log
import com.fivesec.app.data.seed.DefaultAppSeed
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class FiveSecApp : Application() {

    @Inject
    lateinit var defaultAppSeed: DefaultAppSeed

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            // 首启种子属尽力而为：失败只记日志不崩溃应用（也避免测试环境下
            // Robolectric SQLite 影子跨线程异常以未捕获异常毒化无关测试）
            runCatching { defaultAppSeed.ensureSeeded() }
                .onFailure { Log.e("FiveSecApp", "ensureSeeded failed", it) }
        }
    }
}
