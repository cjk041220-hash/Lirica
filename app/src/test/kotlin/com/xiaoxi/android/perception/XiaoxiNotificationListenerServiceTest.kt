package com.xiaoxi.android.perception

import android.app.Notification
import android.os.Bundle
import android.provider.Settings
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaoxi.android.XiaoxiApplication
import com.xiaoxi.android.perception.model.NotificationCategory
import io.mockk.every
import io.mockk.mockk
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
class XiaoxiNotificationListenerServiceTest {

    private lateinit var application: XiaoxiApplication
    private lateinit var service: XiaoxiNotificationListenerService

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        service = XiaoxiNotificationListenerService()
    }

    @Test
    fun testServiceConnectionLifecycle() {
        assertFalse(XiaoxiNotificationListenerService.isServiceConnected)

        service.onListenerConnected()
        assertTrue(XiaoxiNotificationListenerService.isServiceConnected)

        service.onListenerDisconnected()
        assertFalse(XiaoxiNotificationListenerService.isServiceConnected)
    }

    @Test
    fun testOnNotificationPosted_validNotificationEmitted() {
        val sbn = mockk<StatusBarNotification>()
        val extras = Bundle().apply {
            putCharSequence(Notification.EXTRA_TITLE, "微信")
            putCharSequence(Notification.EXTRA_TEXT, "收到一笔转账")
        }
        val notification = Notification().apply {
            this.extras = extras
        }

        every { sbn.packageName } returns "com.tencent.mm"
        every { sbn.postTime } returns 10_000L
        every { sbn.id } returns 201
        every { sbn.tag } returns null
        every { sbn.isOngoing } returns false
        every { sbn.isClearable } returns true
        every { sbn.notification } returns notification

        service.onNotificationPosted(sbn)

        val latest = XiaoxiNotificationListenerService.latestNotification.value
        assertNotNull(latest)
        assertEquals("com.tencent.mm", latest?.packageName)
        assertEquals("微信", latest?.title)
        assertEquals("收到一笔转账", latest?.content)
        assertEquals(NotificationCategory.COMMUNICATION, latest?.category)
    }

    @Test
    fun testOnNotificationPosted_ongoingNotificationIgnored() {
        val sbn = mockk<StatusBarNotification>()
        every { sbn.isOngoing } returns true

        service.onNotificationPosted(sbn)
        // Should not update if ongoing
    }

    @Test
    fun testCreateNotificationListenerSettingsIntent() {
        val intent = XiaoxiNotificationListenerService.createSettingsIntent()
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.action)
        assertEquals(android.content.Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @Test
    fun testIsNotificationListenerEnabled_check() {
        // In Robolectric test context without secure setting enabled, returns false
        val enabled = XiaoxiNotificationListenerService.isNotificationListenerEnabled(application)
        assertFalse(enabled)
    }
}
