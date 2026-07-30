package com.fivesec.app.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

object PackageUtil {

    @Suppress("DEPRECATION")
    fun label(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    @Suppress("DEPRECATION")
    fun icon(pm: PackageManager, pkg: String): Drawable? = try {
        pm.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** 列出非系统的用户安装应用。 */
    @Suppress("DEPRECATION")
    fun installedUserApps(pm: PackageManager): List<InstalledApp> =
        try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                .map { InstalledApp(packageName = it.packageName, label = pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            emptyList()
        }

    data class InstalledApp(val packageName: String, val label: String)

    /** 候选应用 + 是否已在目标清单中（用于添加选择器置灰）。 */
    data class FilteredApp(val app: InstalledApp, val isAdded: Boolean)

    /**
     * 纯函数：按搜索关键词过滤候选应用，并标记已在目标清单中的项。
     * - query 为空：返回全部候选（已添加项仍带 isAdded=true）。
     * - query 非空：按 label（不分大小写）或 packageName（不分大小写）的子串包含匹配。
     */
    fun filterInstalledApps(
        candidates: List<InstalledApp>,
        addedPackageNames: Set<String>,
        query: String,
    ): List<FilteredApp> {
        val q = query.trim().lowercase()
        val matches: (InstalledApp) -> Boolean = if (q.isEmpty()) {
            { true }
        } else {
            { it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        }
        return candidates
            .filter(matches)
            .map { FilteredApp(it, isAdded = it.packageName in addedPackageNames) }
    }
}
