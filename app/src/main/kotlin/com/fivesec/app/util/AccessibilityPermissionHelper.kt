package com.fivesec.app.util

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.fivesec.app.interception.AppBlockerAccessibilityService

object AccessibilityPermissionHelper {

    /** Android 12+ 系统设置支持的深链：直达某个无障碍服务的开关详情页。 */
    private const val ACTION_ACCESSIBILITY_DETAILS_SETTINGS =
        "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"

    /** 判断本应用的无障碍服务是否已启用（兼容完整/缩写两种组件名写法）。 */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val target = ComponentName(context, AppBlockerAccessibilityService::class.java)
        for (entry in enabled.split(':')) {
            val cn = ComponentName.unflattenFromString(entry.trim()) ?: continue
            if (cn == target) return true
        }
        return false
    }

    /** 是否已通过 adb 授予 WRITE_SECURE_SETTINGS（授予即视为同意应用内开启与自动恢复）。 */
    fun hasWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 直接写入安全设置开启本服务（需 WRITE_SECURE_SETTINGS）。
     * 保留用户已开启的其他无障碍服务；任一写入失败返回 false。
     */
    fun enableService(context: Context): Boolean {
        if (!hasWriteSecureSettings(context)) return false
        val resolver = context.contentResolver
        val cn = ComponentName(context, AppBlockerAccessibilityService::class.java)
        val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .filter { it.isNotBlank() }
        val alreadyListed = others.any { ComponentName.unflattenFromString(it) == cn }
        if (!alreadyListed) {
            val updated = (others + cn.flattenToShortString()).joinToString(":")
            if (!Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)) {
                return false
            }
        }
        if (!Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")) return false
        return isServiceEnabled(context)
    }

    /** ROM 后台清理导致服务被关闭时，打开 App 即自动恢复（仅在被授予 WRITE_SECURE_SETTINGS 时生效）。 */
    fun autoEnableIfPermitted(context: Context) {
        if (isServiceEnabled(context) || !hasWriteSecureSettings(context)) return
        enableService(context)
    }

    /** Android 12+ 直达本服务详情开关页；低版本或个别 ROM 不支持时回退无障碍总列表。 */
    fun openAccessibilitySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && openServiceDetailSettings(context)) return
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun openServiceDetailSettings(context: Context): Boolean {
        val cn = ComponentName(context, AppBlockerAccessibilityService::class.java)
        val intent = Intent(ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, cn.flattenToString())
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}
