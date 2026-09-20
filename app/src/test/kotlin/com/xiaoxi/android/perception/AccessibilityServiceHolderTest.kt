package com.xiaoxi.android.perception

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccessibilityServiceHolderTest {

    @Before
    fun setUp() {
        AccessibilityServiceHolder.unregister()
    }

    @Test
    fun testRegistrationAndUnregistration() {
        assertFalse(AccessibilityServiceHolder.isConnected)
        assertNull(AccessibilityServiceHolder.get())

        val mockService = mockk<XiaoxiAccessibilityService>(relaxed = true)
        AccessibilityServiceHolder.register(mockService)

        assertTrue(AccessibilityServiceHolder.isConnected)
        assertEquals(mockService, AccessibilityServiceHolder.get())

        AccessibilityServiceHolder.unregister()
        assertFalse(AccessibilityServiceHolder.isConnected)
        assertNull(AccessibilityServiceHolder.get())
    }
}
