package com.lirica.android.perception

import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.LiricaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = LiricaApplication::class, sdk = [34])
class LiricaAccessibilityServiceTest {

    private lateinit var application: LiricaApplication
    private lateinit var service: LiricaAccessibilityService

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        service = LiricaAccessibilityService()
    }

    @Test
    fun testServiceConstants() {
        assertEquals(150L, LiricaAccessibilityService.NOTIFICATION_TIMEOUT_MS)
        assertFalse(LiricaAccessibilityService.IS_ACCESSIBILITY_TOOL)
        assertTrue(LiricaAccessibilityService.CAN_PERFORM_GESTURES)
        assertTrue(LiricaAccessibilityService.CAN_RETRIEVE_WINDOW_CONTENT)
    }

    @Test
    fun testServiceLifecycle() {
        assertFalse(LiricaAccessibilityService.isServiceConnected)

        service.onServiceConnectedForTest()
        assertTrue(LiricaAccessibilityService.isServiceConnected)

        service.onDestroy()
        assertFalse(LiricaAccessibilityService.isServiceConnected)
    }

    @Test
    fun testOnAccessibilityEventFastEnqueue() {
        service.onServiceConnectedForTest()

        // 预热类加载与 JIT，排除首次调用类加载耗时对 Binder 耗时基准的干扰
        val warmupEvent = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            packageName = "com.warmup"
        }
        service.onAccessibilityEvent(warmupEvent)

        val event = AccessibilityEvent.obtain().apply {
            eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            packageName = "com.tencent.mm"
            className = "com.tencent.mm.ui.LauncherUI"
        }

        // Fast non-blocking enqueue
        val startNs = System.nanoTime()
        service.onAccessibilityEvent(event)
        val elapsedNs = System.nanoTime() - startNs

        // Should complete well within 20ms (< 20_000_000 ns in JVM/Robolectric)
        assertTrue("Event handling took < 20ms: ${elapsedNs}ns", elapsedNs < 20_000_000L)
    }

    @Test
    fun testIsAccessibilityServiceEnabledCheck() {
        val isEnabled = LiricaAccessibilityService.isAccessibilityServiceEnabled(application)
        assertFalse(isEnabled)
    }

    @Test
    fun testCreateAccessibilitySettingsIntent() {
        val intent = LiricaAccessibilityService.createSettingsIntent()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, intent.action)
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
