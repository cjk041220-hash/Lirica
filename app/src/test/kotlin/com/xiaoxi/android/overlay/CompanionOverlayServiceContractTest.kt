package com.xiaoxi.android.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xiaoxi.android.XiaoxiApplication
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
@Config(application = XiaoxiApplication::class, sdk = [34])
class CompanionOverlayServiceContractTest {

    private lateinit var application: XiaoxiApplication

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testOverlayLayoutParamsContract() {
        val params = CompanionOverlayService.createDefaultLayoutParams(
            initialX = 50,
            initialY = 200,
            width = 160,
            height = 160
        )

        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, params.type)
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
        assertTrue((params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0)
        assertEquals(Gravity.TOP or Gravity.START, params.gravity)
        assertEquals(50, params.x)
        assertEquals(200, params.y)
        assertEquals(160, params.width)
        assertEquals(160, params.height)
    }

    @Test
    fun testServiceStartsSticky() {
        val serviceController = Robolectric.buildService(CompanionOverlayService::class.java)
        val service = serviceController.create().get()

        val startResult = service.onStartCommand(Intent(), 0, 1)
        assertEquals(Service.START_STICKY, startResult)

        serviceController.destroy()
    }
}
