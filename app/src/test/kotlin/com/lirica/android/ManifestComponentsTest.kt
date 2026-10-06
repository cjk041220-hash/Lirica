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
    fun testMc51ActionPermissionsDeclaredWithoutCameraOverreach() {
        val manifestContent = File("src/main/AndroidManifest.xml").readText()

        // M5.1 原生动作通道确实需要的两项权限
        assertTrue(
            "写入日程需声明 WRITE_CALENDAR，否则运行时申请必然被拒",
            manifestContent.contains("android.permission.WRITE_CALENDAR")
        )
        assertTrue(
            "亮度写入依赖 WRITE_SETTINGS 特殊权限，Manifest 声明是引导页可用的前提",
            manifestContent.contains("android.permission.WRITE_SETTINGS")
        )

        // 复审 R1 回归防线：手电筒免权限，多声明 CAMERA 会触发弹窗并被系统判定占用相机。
        // 注意必须按"声明行"判定：文档注释里出现 CAMERA 字样是合法的（那正是防回退说明本身）。
        val declaresCamera = manifestContent.lines().any { line ->
            line.trimStart().startsWith("<uses-permission") && line.contains("android.permission.CAMERA")
        }
        assertFalse(
            "手电筒无需 CAMERA 权限（复审 R1），声明它属越权且会造成商店审核问题",
            declaresCamera
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
