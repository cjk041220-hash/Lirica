package com.xiaoxi.android.automation.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GestureExecutorTest {

    private lateinit var accessibilityService: AccessibilityService
    private lateinit var gestureExecutor: GestureExecutorImpl

    @Before
    fun setUp() {
        accessibilityService = mockk(relaxed = true)
        gestureExecutor = GestureExecutorImpl(serviceProvider = { accessibilityService })
    }

    @Test
    fun testBuildPath_clickAndLongPress() {
        val clickPath = gestureExecutor.buildPath(GestureSpec.Click(100f, 200f))
        assertNotNull(clickPath)

        val longPressPath = gestureExecutor.buildPath(GestureSpec.LongPress(300f, 400f))
        assertNotNull(longPressPath)
    }

    @Test
    fun testBuildPath_swipeLinearAndHumanoid() {
        val linearSwipe = gestureExecutor.buildPath(
            GestureSpec.Swipe(100f, 100f, 500f, 500f, humanoidCurve = false)
        )
        assertNotNull(linearSwipe)

        val humanoidSwipe = gestureExecutor.buildPath(
            GestureSpec.Swipe(100f, 100f, 500f, 500f, humanoidCurve = true)
        )
        assertNotNull(humanoidSwipe)
    }

    @Test
    fun testExecute_serviceUnavailable_returnsServiceUnavailable() = runTest {
        val executorWithoutService = GestureExecutorImpl(serviceProvider = { null })
        val result = executorWithoutService.execute(GestureSpec.Click(100f, 100f))
        assertEquals(GestureResult.ServiceUnavailable, result)
    }

    @Test
    fun testExecute_dispatchSuccess_returnsCompleted() = runTest {
        val callbackSlot = slot<AccessibilityService.GestureResultCallback>()
        every {
            accessibilityService.dispatchGesture(any(), capture(callbackSlot), any())
        } answers {
            callbackSlot.captured.onCompleted(mockk())
            true
        }

        val result = gestureExecutor.execute(GestureSpec.Click(100f, 100f, durationMs = 150L))
        assertTrue(result is GestureResult.Completed)
        assertEquals(150L, (result as GestureResult.Completed).durationMs)
    }

    @Test
    fun testExecute_dispatchCancelled_returnsCancelled() = runTest {
        val callbackSlot = slot<AccessibilityService.GestureResultCallback>()
        every {
            accessibilityService.dispatchGesture(any(), capture(callbackSlot), any())
        } answers {
            callbackSlot.captured.onCancelled(mockk())
            true
        }

        val result = gestureExecutor.execute(GestureSpec.Click(100f, 100f))
        assertTrue(result is GestureResult.Cancelled)
        assertEquals("System cancelled gesture", (result as GestureResult.Cancelled).reason)
    }

    @Test
    fun testExecute_dispatchReturnsFalse_returnsCancelled() = runTest {
        every {
            accessibilityService.dispatchGesture(any(), any(), any())
        } returns false

        val result = gestureExecutor.execute(GestureSpec.Click(100f, 100f))
        assertTrue(result is GestureResult.Cancelled)
        assertTrue((result as GestureResult.Cancelled).reason.contains("dispatchGesture returned false"))
    }

    @Test
    fun testExecute_timeout_returnsTimedOut() = runTest {
        // Never triggers callback
        every {
            accessibilityService.dispatchGesture(any(), any(), any())
        } returns true

        val result = gestureExecutor.execute(GestureSpec.Click(100f, 100f), timeoutMs = 100L)
        assertTrue(result is GestureResult.TimedOut)
        assertEquals(100L, (result as GestureResult.TimedOut).timeoutMs)
    }
}
