package com.fivesec.app

import android.app.Application
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
        appScope.launch { defaultAppSeed.ensureSeeded() }
    }
}
