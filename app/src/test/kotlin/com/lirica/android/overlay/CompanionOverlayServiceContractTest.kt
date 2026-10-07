package com.lirica.android.overlay

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
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
class CompanionOverlayServiceContractTest {

    private lateinit var application: LiricaApplication

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
        assertTrue("必须声明 FLAG_NOT_TOUCH_MODAL 允许悬浮窗外的触摸穿透", (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0)
        assertTrue("必须声明 FLAG_WATCH_OUTSIDE_TOUCH 支持点击外部自动收起", (params.flags and WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0)
        assertEquals("严禁声明 FLAG_LAYOUT_NO_LIMITS 避免鸿蒙等 ROM 触摸区域全屏穿透拦截", 0, params.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
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
