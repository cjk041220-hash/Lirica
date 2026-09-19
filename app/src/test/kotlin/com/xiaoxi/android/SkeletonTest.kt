package com.xiaoxi.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkeletonTest {
    @Test
    fun testProjectIdentity() {
        val appName = "xiaoxi-android"
        val version = "2.0.0"
        assertEquals("xiaoxi-android", appName)
        assertTrue(version.startsWith("2."))
    }
}
