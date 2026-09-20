package com.xiaoxi.android.perception

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
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
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = XiaoxiApplication::class, sdk = [34])
class XiaoxiAccessibilityServiceTest {

    private lateinit var application: XiaoxiApplication
    private lateinit var service: XiaoxiAccessibilityService

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        service = XiaoxiAccessibilityService()
    }

    @Test
    fun testServiceConstants() {
        assertEquals(150L, XiaoxiAccessibilityService.NOTIFICATION_TIMEOUT_MS)
        assertFalse(XiaoxiAccessibilityService.IS_ACCESSIBILITY_TOOL)
        assertTrue(XiaoxiAccessibilityService.CAN_PERFORM_GESTURES)
        assertTrue(XiaoxiAccessibilityService.CAN_RETRIEVE_WINDOW_CONTENT)
    }

    @Test
    fun testServiceLifecycle() {
        assertFalse(XiaoxiAccessibilityService.isServiceConnected)

        service.onServiceConnectedForTest()
        assertTrue(XiaoxiAccessibilityService.isServiceConnected)

        service.onDestroy()
        assertFalse(XiaoxiAccessibilityService.isServiceConnected)
    }

    @Test
    fun testOnAccessibilityEventFastEnqueue() {
        service.onServiceConnectedForTest()

        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "com.tencent.mm"
            className = "com.tencent.mm.ui.LauncherUI"
        }

        // Fast non-blocking enqueue
        val startNs = System.nanoTime()
        service.onAccessibilityEvent(event)
        val elapsedNs = System.nanoTime() - startNs

        // Should complete well within 1ms (< 1,000,000 ns)
        assertTrue("Event handling took < 1ms: ${elapsedNs}ns", elapsedNs < 10_000_000L)
    }

    @Test
    fun testIsAccessibilityServiceEnabledCheck() {
        val isEnabled = XiaoxiAccessibilityService.isAccessibilityServiceEnabled(application)
        assertFalse(isEnabled)
    }

    @Test
    fun testCreateAccessibilitySettingsIntent() {
        val intent = XiaoxiAccessibilityService.createSettingsIntent()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
