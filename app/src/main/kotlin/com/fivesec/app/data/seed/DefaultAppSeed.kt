package com.fivesec.app.data.seed

import com.fivesec.app.data.repository.TargetAppRepository
import com.fivesec.app.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton

/** 内置默认目标应用清单（首次启动写入，使 US1 无需配置 UI 即可工作）。 */
@Singleton
class DefaultAppSeed @Inject constructor(
    private val targetAppRepository: TargetAppRepository,
    private val timeProvider: TimeProvider,
) {
    suspend fun ensureSeeded() {
        if (targetAppRepository.count() > 0) return
        val now = timeProvider.now()
        targetAppRepository.insertAll(
            DEFAULT_APPS.map { (packageName, appName) ->
                com.fivesec.app.domain.model.TargetApp(
                    packageName = packageName,
                    appName = appName,
                    isEnabled = true,
                    isDefault = true,
                    addedAt = now,
                )
            }
        )
    }

    companion object {
        val DEFAULT_APPS = listOf(
            "com.ss.android.ugc.aweme" to "抖音",
            "com.xingin.xhs" to "小红书",
            "tv.danmaku.bili" to "哔哩哔哩",
        )
    }
}
