package com.lirica.android.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.LiricaApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = LiricaApplication::class, sdk = [34])
class KeepAliveServiceTest {

    private lateinit var application: LiricaApplication

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testNotificationChannelAndAttributes() {
        KeepAliveService.createNotificationChannel(application)
        val notificationManager = application.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = notificationManager.getNotificationChannel(KeepAliveService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(KeepAliveService.CHANNEL_NAME, channel.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
    }

    @Test
    fun testBuildOngoingNotification() {
        val notification = KeepAliveService.buildNotification(application)
        assertNotNull(notification)
        assertTrue((notification.flags and NotificationCompat.FLAG_ONGOING_EVENT) != 0)
        assertNotNull(notification.contentIntent)

        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        assertNotNull(title)
        assertTrue(title!!.contains("Lirica"))
    }

    @Test
    fun testServiceStartsSticky() {
        val serviceController = Robolectric.buildService(KeepAliveService::class.java)
        val service = serviceController.create().get()

        val startResult = service.onStartCommand(Intent(), 0, 1)
        assertEquals(Service.START_STICKY, startResult)

        serviceController.destroy()
    }

    @Test
    fun testServiceStopAction() {
        val serviceController = Robolectric.buildService(KeepAliveService::class.java)
        val service = serviceController.create().get()

        val stopIntent = Intent(application, KeepAliveService::class.java).apply {
            action = KeepAliveService.ACTION_STOP_SERVICE
        }
        val startResult = service.onStartCommand(stopIntent, 0, 2)
        assertEquals(Service.START_NOT_STICKY, startResult)

        serviceController.destroy()
    }

    @Test
    fun testStartAndStopStaticHelpers() {
        KeepAliveService.start(application)
        val startedServiceIntent = shadowOf(application).nextStartedService
        assertNotNull(startedServiceIntent)
        assertEquals(KeepAliveService::class.java.name, startedServiceIntent.component?.className)

        KeepAliveService.stop(application)
        val stopServiceIntent = shadowOf(application).nextStartedService
        assertNotNull(stopServiceIntent)
        assertEquals(KeepAliveService.ACTION_STOP_SERVICE, stopServiceIntent.action)
    }
}
