package com.lirica.android.automation.gesture

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

    @Test
    fun testExecute_passwordTarget_physicallyBlocked_returnsCancelledWithHonestReason() = runTest {
        val gesture = GestureSpec.Click(
            x = 200f,
            y = 300f,
            targetResourceId = "com.example.app:id/edit_password",
            isPasswordTarget = true,
            targetPackage = "com.example.app"
        )

        val result = gestureExecutor.execute(gesture)
        assertTrue(result is GestureResult.Cancelled)
        val cancelled = result as GestureResult.Cancelled
        assertEquals("该操作属于受限范围，已停止", cancelled.reason)
        assertEquals("该操作属于受限范围，已停止", gestureExecutor.lastHonestReason)
        // 物理手势严格未下发
        io.mockk.verify(exactly = 0) { accessibilityService.dispatchGesture(any(), any(), any()) }
    }

    @Test
    fun testExecute_criticalPaymentKeyword_physicallyBlocked_returnsCancelledWithHonestReason() = runTest {
        val gesture = GestureSpec.Click(
            x = 500f,
            y = 800f,
            targetText = "立即支付 88.00元",
            targetPackage = "com.sankuai.meituan"
        )

        val result = gestureExecutor.execute(gesture)
        assertTrue(result is GestureResult.Cancelled)
        val cancelled = result as GestureResult.Cancelled
        assertEquals("该操作属于受限范围，已停止", cancelled.reason)
        assertEquals("该操作属于受限范围，已停止", gestureExecutor.lastHonestReason)
        io.mockk.verify(exactly = 0) { accessibilityService.dispatchGesture(any(), any(), any()) }
    }

    @Test
    fun testExecute_communicationAppTextInput_physicallyBlocked_returnsCancelled() = runTest {
        val gesture = GestureSpec.Click(
            x = 300f,
            y = 500f,
            targetPackage = "com.tencent.mm",
            targetText = "发送消息",
            isTextInput = true
        )

        val result = gestureExecutor.execute(gesture)
        assertTrue(result is GestureResult.Cancelled)
        val cancelled = result as GestureResult.Cancelled
        assertEquals("该操作属于受限范围，已停止", cancelled.reason)
        assertEquals("该操作属于受限范围，已停止", gestureExecutor.lastHonestReason)
        io.mockk.verify(exactly = 0) { accessibilityService.dispatchGesture(any(), any(), any()) }
    }

    @Test
    fun testExecute_communicationAppClick_allowed_proceedsToDispatch() = runTest {
        val callbackSlot = slot<AccessibilityService.GestureResultCallback>()
        every {
            accessibilityService.dispatchGesture(any(), capture(callbackSlot), any())
        } answers {
            callbackSlot.captured.onCompleted(mockk())
            true
        }

        val gesture = GestureSpec.Click(
            x = 300f,
            y = 500f,
            targetPackage = "com.tencent.mm",
            targetText = "朋友圈",
            isTextInput = false
        )

        val result = gestureExecutor.execute(gesture)
        assertTrue(result is GestureResult.Completed)
        io.mockk.verify(atLeast = 1) { accessibilityService.dispatchGesture(any(), any(), any()) }
    }
}
