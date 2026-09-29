package com.lirica.android.receiver

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lirica.android.LiricaApplication
import com.lirica.android.service.KeepAliveService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = LiricaApplication::class, sdk = [34])
class BootReceiverTest {

    private lateinit var application: LiricaApplication
    private lateinit var receiver: BootReceiver

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        receiver = BootReceiver()
    }

    @Test
    fun testBootCompletedStartsKeepAliveService() {
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(application, intent)

        val nextService = shadowOf(application).nextStartedService
        assertNotNull(nextService)
        assertEquals(KeepAliveService::class.java.name, nextService.component?.className)
    }

    @Test
    fun testPackageReplacedStartsKeepAliveService() {
        val intent = Intent(Intent.ACTION_MY_PACKAGE_REPLACED)
        receiver.onReceive(application, intent)

        val nextService = shadowOf(application).nextStartedService
        assertNotNull(nextService)
        assertEquals(KeepAliveService::class.java.name, nextService.component?.className)
    }

    @Test
    fun testUnrelatedActionDoesNotStartService() {
        val intent = Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED)
        receiver.onReceive(application, intent)

        val nextService = shadowOf(application).nextStartedService
        assertNull(nextService)
    }
}
