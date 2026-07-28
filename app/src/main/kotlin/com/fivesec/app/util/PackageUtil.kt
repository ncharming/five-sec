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
}
