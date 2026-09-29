package com.lirica.android

import com.lirica.android.overlay.CompanionOverlayService
import com.lirica.android.perception.LiricaAccessibilityService
import com.lirica.android.perception.LiricaNotificationListenerService
import com.lirica.android.service.KeepAliveService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestComponentsTest {

    @Test
    fun testAccessibilityServiceConfigurationContract() {
        assertEquals(150L, LiricaAccessibilityService.NOTIFICATION_TIMEOUT_MS)
        assertFalse(LiricaAccessibilityService.IS_ACCESSIBILITY_TOOL)
        assertTrue(LiricaAccessibilityService.CAN_PERFORM_GESTURES)
        assertTrue(LiricaAccessibilityService.CAN_RETRIEVE_WINDOW_CONTENT)
        assertTrue(LiricaAccessibilityService.CAN_TAKE_SCREENSHOT)
    }

    @Test
    fun testAccessibilityServiceConfigXmlExistsAndValid() {
        val xmlFile = File("src/main/res/xml/accessibility_service_config.xml")
        assertTrue("accessibility_service_config.xml must exist", xmlFile.exists())

        val content = xmlFile.readText()
        assertTrue(content.contains("android:notificationTimeout=\"150\""))
        assertTrue(content.contains("android:canRetrieveWindowContent=\"true\""))
        assertTrue(content.contains("android:canPerformGestures=\"true\""))
        assertTrue(content.contains("android:canTakeScreenshot=\"true\""))
        assertTrue(content.contains("android:isAccessibilityTool=\"false\""))
    }

    @Test
    fun testServiceClassHierarchies() {
        assertTrue(
            "LiricaAccessibilityService must inherit from android.accessibilityservice.AccessibilityService",
            android.accessibilityservice.AccessibilityService::class.java.isAssignableFrom(LiricaAccessibilityService::class.java)
        )
        assertTrue(
            "LiricaNotificationListenerService must inherit from android.service.notification.NotificationListenerService",
            android.service.notification.NotificationListenerService::class.java.isAssignableFrom(LiricaNotificationListenerService::class.java)
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
        assertTrue(manifestContent.contains("android.permission.RECEIVE_BOOT_COMPLETED"))
        assertTrue(manifestContent.contains("moe.shizuku.manager.permission.API_V23"))
        assertTrue(manifestContent.contains("<queries>"))

        // Services & Receivers
        assertTrue(manifestContent.contains(".perception.LiricaAccessibilityService"))
        assertTrue(manifestContent.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(manifestContent.contains(".perception.LiricaNotificationListenerService"))
        assertTrue(manifestContent.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
        assertTrue(manifestContent.contains(".overlay.CompanionOverlayService"))
        assertTrue(manifestContent.contains(".service.KeepAliveService"))
        assertTrue(manifestContent.contains(".receiver.BootReceiver"))
    }
}
