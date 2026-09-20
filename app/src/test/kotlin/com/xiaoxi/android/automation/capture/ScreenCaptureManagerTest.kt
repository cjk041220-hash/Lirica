package com.xiaoxi.android.automation.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun testCaptureScreen_serviceNull_returnsNull() = kotlinx.coroutines.test.runTest {
        val manager = ScreenCaptureManager(serviceProvider = { null })
        assertNull(manager.captureScreen())
    }
}
