package com.xiaoxi.android

import com.xiaoxi.android.overlay.CompanionOverlayService
import com.xiaoxi.android.perception.XiaoxiAccessibilityService
import com.xiaoxi.android.perception.XiaoxiNotificationListenerService
import com.xiaoxi.android.service.KeepAliveService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestComponentsTest {

    @Test
    fun testAccessibilityServiceConfigurationContract() {
        assertEquals(150L, XiaoxiAccessibilityService.NOTIFICATION_TIMEOUT_MS)
        assertFalse(XiaoxiAccessibilityService.IS_ACCESSIBILITY_TOOL)
        assertTrue(XiaoxiAccessibilityService.CAN_PERFORM_GESTURES)
        assertTrue(XiaoxiAccessibilityService.CAN_RETRIEVE_WINDOW_CONTENT)
    }

    @Test
    fun testAccessibilityServiceConfigXmlExistsAndValid() {
        val xmlFile = File("src/main/res/xml/accessibility_service_config.xml")
        assertTrue("accessibility_service_config.xml must exist", xmlFile.exists())

        val content = xmlFile.readText()
        assertTrue(content.contains("android:notificationTimeout=\"150\""))
        assertTrue(content.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(content.contains("android:canPerformGestures=\"true\""))
        assertTrue(content.contains("android:isAccessibilityTool=\"false\""))
    }

    @Test
    fun testServiceClassHierarchies() {
        assertTrue(
            "XiaoxiAccessibilityService must inherit from android.accessibilityservice.AccessibilityService",
            android.accessibilityservice.AccessibilityService::class.java.isAssignableFrom(XiaoxiAccessibilityService::class.java)
        )
        assertTrue(
            "XiaoxiNotificationListenerService must inherit from android.service.notification.NotificationListenerService",
            android.service.notification.NotificationListenerService::class.java.isAssignableFrom(XiaoxiNotificationListenerService::class.java)
        )
        assertTrue(
            "CompanionOverlayService must inherit from androidx.lifecycle.LifecycleService",
            androidx.lifecycle.LifecycleService::class.java.isAssignableFrom(CompanionOverlayService::class.java)
        )
        assertTrue(
            "KeepAliveService must inherit from androidx.lifecycle.LifecycleService",
            androidx.lifecycle.LifecycleService::class.java.isAssignableFrom(KeepAliveService::class.java)
        )
    }

    @Test
    fun testManifestDeclaresCoreServicesAndPermissions() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        assertTrue("AndroidManifest.xml must exist", manifestFile.exists())
        val manifestContent = manifestFile.readText()

        // Permissions
        assertTrue(manifestContent.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertTrue(manifestContent.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(manifestContent.contains("android.permission.FOREGROUND_SERVICE_SPECIAL_USE"))
        assertTrue(manifestContent.contains("android.permission.PACKAGE_USAGE_STATS"))
        assertTrue(manifestContent.contains("android.permission.POST_NOTIFICATIONS"))

        // Services
        assertTrue(manifestContent.contains(".perception.XiaoxiAccessibilityService"))
        assertTrue(manifestContent.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(manifestContent.contains(".perception.XiaoxiNotificationListenerService"))
        assertTrue(manifestContent.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(manifestContent.contains(".overlay.CompanionOverlayService"))
        assertTrue(manifestContent.contains(".service.KeepAliveService"))
    }
}
