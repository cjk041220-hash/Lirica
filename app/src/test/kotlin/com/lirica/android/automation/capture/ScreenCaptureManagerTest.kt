package com.lirica.android.automation.capture

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ScreenCaptureManagerTest {

    @Test
    fun testFrameCaptureResult_modelContract() {
        val dummyBytes = byteArrayOf(1, 2, 3)
        val frame = FrameCaptureResult(
            bytes = dummyBytes,
            widthPx = 1080,
            heightPx = 2400,
            timestamp = 1726800000000L
        )

        assertEquals(1080, frame.widthPx)
        assertEquals(2400, frame.heightPx)
        assertEquals(3, frame.bytes.size)
        assertEquals(1726800000000L, frame.timestamp)
    }

    @Test
    fun testCaptureScreen_serviceNull_returnsNull() = runTest {
        val manager = ScreenCaptureManager(
            serviceProvider = { null },
            currentPackageProvider = { "com.example.app" }
        )
        assertNull(manager.captureScreen())
    }

    @Test
    fun testCaptureScreen_sensitiveApp_blockedByPrivacyGate() = runTest {
        var captureCallCount = 0
        val dummyFrame = FrameCaptureResult(byteArrayOf(1, 2), 1080, 2400)
        val manager = ScreenCaptureManager(
            currentPackageProvider = { "com.eg.android.AlipayGphone" }, // 支付宝敏感包名
            captureExecutor = {
                captureCallCount++
                dummyFrame
            }
        )

        // 命中毒株敏感金融应用黑名单：必须物理断流，绝不调用底层截屏
        val result = manager.captureScreen()
        assertNull("金融应用前台必须物理断流拦截截屏", result)
        assertEquals("截屏执行器不应被调用", 0, captureCallCount)
    }

    @Test
    fun testCaptureScreen_throttling_reusesCachedFrameWithin250ms() = runTest {
        var captureCallCount = 0
        var mockTime = 1000L
        val frame1 = FrameCaptureResult(byteArrayOf(1), 1080, 2400, timestamp = 1000L)
        val frame2 = FrameCaptureResult(byteArrayOf(2), 1080, 2400, timestamp = 1200L)

        val manager = ScreenCaptureManager(
            currentPackageProvider = { "com.example.normalapp" },
            minIntervalMs = 250L,
            timeProvider = { mockTime },
            captureExecutor = {
                captureCallCount++
                if (captureCallCount == 1) frame1 else frame2
            }
        )

        // 第一次捕获
        val res1 = manager.captureScreen()
        assertNotNull(res1)
        assertSame(frame1, res1)
        assertEquals(1, captureCallCount)

        // 100ms 后再次调用（未越过 250ms 节流窗口）：应复用缓存帧，不触发新捕获
        mockTime = 1100L
        val res2 = manager.captureScreen()
        assertSame("节流期内应复用上一帧缓存", frame1, res2)
        assertEquals("节流期内不应触发新截帧", 1, captureCallCount)

        // 300ms 后再次调用（已越过 250ms 节流窗口）：应触发新截帧
        mockTime = 1350L
        val res3 = manager.captureScreen()
        assertSame("越过节流窗口后应获取新帧", frame2, res3)
        assertEquals(2, captureCallCount)
    }

    @Test
    fun testCaptureScreen_withExecutorSeam_returnsFrame() = runTest {
        val expectedFrame = FrameCaptureResult(byteArrayOf(10, 20), 1080, 2400)
        val manager = ScreenCaptureManager(
            currentPackageProvider = { "com.tencent.mm" },
            captureExecutor = { expectedFrame }
        )

        val result = manager.captureScreen()
        assertNotNull(result)
        assertEquals(1080, result?.widthPx)
        assertEquals(2400, result?.heightPx)
        assertEquals(2, result?.bytes?.size)
    }
}
