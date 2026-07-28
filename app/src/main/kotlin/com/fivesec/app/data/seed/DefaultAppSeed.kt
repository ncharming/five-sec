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
            DEFAULT_PACKAGES.map { pkg ->
                com.fivesec.app.domain.model.TargetApp(
                    packageName = pkg,
                    isEnabled = true,
                    isDefault = true,
                    addedAt = now,
                )
            }
        )
    }

    companion object {
        val DEFAULT_PACKAGES = listOf(
            "com.ss.android.ugc.aweme", // 抖音
            "com.xingin.xhs",           // 小红书
            "tv.danmaku.bili",          // 哔哩哔哩
        )
    }
}
