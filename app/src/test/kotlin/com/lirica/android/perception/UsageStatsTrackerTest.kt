package com.lirica.android.perception

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.LiricaApplication
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.ScreenState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppOpsManager
import org.robolectric.shadows.ShadowUsageStatsManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(application = LiricaApplication::class, sdk = [34])
class UsageStatsTrackerTest {

    private lateinit var application: LiricaApplication
    private lateinit var tracker: UsageStatsTracker

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        tracker = UsageStatsTracker()
    }

    @Test
    fun testPermissionCheck_whenNotGranted_returnsFalse() {
        val appOps = application.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val shadowAppOps: ShadowAppOpsManager = shadowOf(appOps)
        shadowAppOps.setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            application.packageName,
            AppOpsManager.MODE_DEFAULT
        )

        assertFalse(tracker.hasUsageStatsPermission(application))
    }

    @Test
    fun testPermissionCheck_whenGranted_returnsTrue() {
        val appOps = application.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val shadowAppOps: ShadowAppOpsManager = shadowOf(appOps)
        shadowAppOps.setMode(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            application.packageName,
            AppOpsManager.MODE_ALLOWED
        )

        assertTrue(tracker.hasUsageStatsPermission(application))
    }

    @Test
    fun testCreatePermissionIntent() {
        val intent = tracker.createPermissionSettingsIntent(application)
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS, intent.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun testGetForegroundApp_whenEmptyEvents_returnsNull() {
        val result = tracker.getForegroundApp(
            context = application,
            currentTimeMs = 100_000L
        )
        assertNull(result)
    }

    @Test
    fun testGetForegroundApp_withRobolectricEvents() {
        val usageStatsManager = application.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val shadowUsageStats: ShadowUsageStatsManager = shadowOf(usageStatsManager)

        val t1 = 50_000L
        val t2 = 80_000L
        shadowUsageStats.addEvent("com.old.app", t1, 1 /* ACTIVITY_RESUMED */)
        shadowUsageStats.addEvent("com.tencent.mm", t2, 1 /* ACTIVITY_RESUMED */)

        val result = tracker.getForegroundApp(
            context = application,
            currentTimeMs = 100_000L,
            lookbackMs = 60_000L
        )

        assertNotNull(result)
        assertEquals("com.tencent.mm", result?.packageName)
        assertEquals(t2, result?.timestamp)
    }

    @Test
    fun testDwellDurationAndAppSwitching() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        var simulatedApp: ForegroundAppInfo? = ForegroundAppInfo("com.tencent.mm", "LauncherUI", 1000L)
        val screenStateFlow = MutableStateFlow(ScreenState.UNLOCKED)

        try {
            tracker.startTracking(
                scope = this,
                pollIntervalMs = 1000L,
                screenStateFlow = screenStateFlow,
                dispatcher = testDispatcher,
                appProvider = { simulatedApp }
            )

            // Poll 1: Initial detection
            testScheduler.advanceTimeBy(1000L)
            testScheduler.runCurrent()
            var current = tracker.foregroundApp.value
            assertNotNull(current)
            assertEquals("com.tencent.mm", current?.packageName)
            assertEquals(0L, current?.dwellDurationMs)

            // Poll 2: Same package stays in foreground
            testScheduler.advanceTimeBy(1000L)
            testScheduler.runCurrent()
            current = tracker.foregroundApp.value
            assertNotNull(current)
            assertEquals("com.tencent.mm", current?.packageName)
            assertEquals(1000L, current?.dwellDurationMs)

            // Poll 3: Still same package
            testScheduler.advanceTimeBy(2000L)
            testScheduler.runCurrent()
            current = tracker.foregroundApp.value
            assertEquals("com.tencent.mm", current?.packageName)
            assertEquals(3000L, current?.dwellDurationMs)

            // App switches to Bilibili
            simulatedApp = ForegroundAppInfo("tv.danmaku.bili", "MainActivity", 5000L)
            testScheduler.advanceTimeBy(1000L)
            testScheduler.runCurrent()
            current = tracker.foregroundApp.value
            assertEquals("tv.danmaku.bili", current?.packageName)
            assertEquals(0L, current?.dwellDurationMs)

            // Stays on Bilibili for another interval
            testScheduler.advanceTimeBy(1000L)
            testScheduler.runCurrent()
            current = tracker.foregroundApp.value
            assertEquals("tv.danmaku.bili", current?.packageName)
            assertEquals(1000L, current?.dwellDurationMs)

            // Screen turns OFF -> Polling should pause and not advance dwell duration
            screenStateFlow.value = ScreenState.OFF
            testScheduler.advanceTimeBy(3000L)
            testScheduler.runCurrent()
            current = tracker.foregroundApp.value
            assertEquals(1000L, current?.dwellDurationMs)
        } finally {
            tracker.stopTracking()
        }
    }
}
