package com.xiaoxi.android.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaoxi.android.XiaoxiApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@RunWith(AndroidJUnit4::class)
@Config(application = XiaoxiApplication::class, sdk = [34])
class BatteryOptimizationHelperTest {

    private lateinit var application: XiaoxiApplication
    private lateinit var powerManager: PowerManager
    private lateinit var shadowPowerManager: ShadowPowerManager

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        powerManager = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowPowerManager = shadowOf(powerManager)
    }

    @Test
    fun testIsIgnoringBatteryOptimizations() {
        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, false)
        assertFalse(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(application))

        shadowPowerManager.setIgnoringBatteryOptimizations(application.packageName, true)
        assertTrue(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(application))
    }

    @Test
    fun testCreateRequestIgnoreBatteryOptimizationsIntent() {
        val intent = BatteryOptimizationHelper.createRequestIgnoreBatteryOptimizationsIntent("com.xiaoxi.android")
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, intent.action)
        assertEquals(Uri.parse("package:com.xiaoxi.android"), intent.data)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testCreateVendorBatteryOptimizationIntents() {
        val vendorIntents = BatteryOptimizationHelper.createVendorBatteryOptimizationIntents(application)
        assertNotNull(vendorIntents)
        assertTrue(vendorIntents.isNotEmpty())
        for (intent in vendorIntents) {
            assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
        }
    }

    @Test
    fun testOpenBatteryOptimizationSettingsFallsBackGracefully() {
        val opened = BatteryOptimizationHelper.openBatteryOptimizationSettings(application)
        assertTrue(opened)

        val nextStartedActivity = shadowOf(application).nextStartedActivity
        assertNotNull(nextStartedActivity)
    }
}
