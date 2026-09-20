package com.xiaoxi.android.common.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class XiaoxiLogTest {

    @Before
    fun setUp() {
        XiaoxiLog.clear()
    }

    @Test
    fun testLogRecording_recordsToRingBuffer() {
        XiaoxiLog.d("TestTag", "Debug message")
        XiaoxiLog.i("TestTag", "Info message")
        XiaoxiLog.w("TestTag", "Warn message", RuntimeException("Warn cause"))
        XiaoxiLog.e("TestTag", "Error message", IllegalStateException("Error cause"))

        val logs = XiaoxiLog.getRecentLogs()
        assertEquals(4, logs.size)
        assertTrue(logs[0].contains("[DEBUG][TestTag] Debug message"))
        assertTrue(logs[1].contains("[INFO][TestTag] Info message"))
        assertTrue(logs[2].contains("[WARN][TestTag] Warn message"))
        assertTrue(logs[3].contains("[ERROR][TestTag] Error message"))
        assertTrue(logs[3].contains("IllegalStateException"))
    }

    @Test
    fun testRingBufferCapacity_capsAt200AndDropsOldest() {
        for (i in 1..250) {
            XiaoxiLog.i("Tag", "Message #$i")
        }

        val logs = XiaoxiLog.getRecentLogs()
        assertEquals(200, logs.size)
        // 应该包含最后写入的 Message #250，而前 50 条被挤出
        assertTrue(logs.last().contains("Message #250"))
        assertTrue(logs.first().contains("Message #51"))
    }
}
