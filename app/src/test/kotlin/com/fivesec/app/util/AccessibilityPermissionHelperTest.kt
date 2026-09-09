package com.fivesec.app.util

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.fivesec.app.interception.AppBlockerAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** 覆盖无障碍服务状态检测、一键开启（WRITE_SECURE_SETTINGS）与跳转降级逻辑。 */
@RunWith(RobolectricTestRunner::class)
class AccessibilityPermissionHelperTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val serviceCn = ComponentName(context, AppBlockerAccessibilityService::class.java)
    private val serviceFlat = serviceCn.flattenToShortString()

    @Before
    fun setUp() {
        setSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        setSecure(Settings.Secure.ACCESSIBILITY_ENABLED, "0")
    }

    @Test
    fun `完整组件名形式可识别服务已开启`() {
        setEnabledServices(serviceCn.flattenToString())
        assertTrue(AccessibilityPermissionHelper.isServiceEnabled(context))
    }

    @Test
    fun `缩写组件名形式也可识别且不影响其他服务`() {
        setEnabledServices("com.other.svc/.OtherService", serviceFlat)
        assertTrue(AccessibilityPermissionHelper.isServiceEnabled(context))
    }

    @Test
    fun `列表中无本服务时判定未开启`() {
        setEnabledServices("com.other.svc/.OtherService")
        assertFalse(AccessibilityPermissionHelper.isServiceEnabled(context))
    }

    @Test
    fun `未授权时一键开启不生效`() {
        assertFalse(AccessibilityPermissionHelper.enableService(context))
        assertFalse(AccessibilityPermissionHelper.isServiceEnabled(context))
    }

    @Test
    fun `授权后一键开启并保留其他服务`() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        setEnabledServices("com.other.svc/.OtherService")

        assertTrue(AccessibilityPermissionHelper.enableService(context))

        val services = getSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertEquals(setOf("com.other.svc/.OtherService", serviceFlat), services.split(':').toSet())
        assertEquals("1", getSecure(Settings.Secure.ACCESSIBILITY_ENABLED))
        assertTrue(AccessibilityPermissionHelper.isServiceEnabled(context))
    }

    @Test
    fun `重复一键开启不产生重复条目`() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

        assertTrue(AccessibilityPermissionHelper.enableService(context))
        assertTrue(AccessibilityPermissionHelper.enableService(context))

        val services = getSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        assertEquals(1, services.split(':').count { it == serviceFlat })
    }

    @Test
    fun `授权后打开应用可自动恢复服务`() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)

        AccessibilityPermissionHelper.autoEnableIfPermitted(context)

        assertTrue(AccessibilityPermissionHelper.isServiceEnabled(context))
    }

    @Test
    fun `未授权时打开应用不自动恢复`() {
        AccessibilityPermissionHelper.autoEnableIfPermitted(context)
        assertFalse(AccessibilityPermissionHelper.isServiceEnabled(context))
    }

    @Test
    fun `服务已开启时自动恢复不重复写入`() {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        setEnabledServices(serviceFlat)

        AccessibilityPermissionHelper.autoEnableIfPermitted(context)

        assertEquals(serviceFlat, getSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }

    @Test
    fun `详情页无法解析时回退无障碍总列表`() {
        AccessibilityPermissionHelper.openAccessibilitySettings(context)

        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, Shadows.shadowOf(context).nextStartedActivity.action)
    }

    @Test
    @Config(sdk = [31])
    fun `Android 12 有详情页时直达服务详情`() {
        Shadows.shadowOf(context.packageManager)
            .addResolveInfoForIntent(Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS"), ResolveInfo())

        AccessibilityPermissionHelper.openAccessibilitySettings(context)

        val started = Shadows.shadowOf(context).nextStartedActivity
        assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", started.action)
        assertEquals(serviceCn.flattenToString(), started.getStringExtra(Intent.EXTRA_COMPONENT_NAME))
    }

    @Test
    @Config(sdk = [30])
    fun `Android 11 及以下始终打开总列表`() {
        AccessibilityPermissionHelper.openAccessibilitySettings(context)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, Shadows.shadowOf(context).nextStartedActivity.action)
    }

    private fun setEnabledServices(vararg flat: String) {
        setSecure(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, flat.joinToString(":"))
    }

    private fun setSecure(key: String, value: String) {
        Settings.Secure.putString(context.contentResolver, key, value)
    }

    private fun getSecure(key: String): String? =
        Settings.Secure.getString(context.contentResolver, key)
}
