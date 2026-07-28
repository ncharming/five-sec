package com.fivesec.app.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.fivesec.app.interception.AppBlockerAccessibilityService

object AccessibilityPermissionHelper {

    /** 判断本应用的无障碍服务是否已启用。 */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val cn = ComponentName(context, AppBlockerAccessibilityService::class.java)
        val flat = cn.flattenToString()
        val flatShort = context.packageName + "/" + cn.className
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        while (splitter.hasNext()) {
            val entry = splitter.next().trim()
            if (entry.equals(flat, ignoreCase = true) || entry.equals(flatShort, ignoreCase = true)) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
