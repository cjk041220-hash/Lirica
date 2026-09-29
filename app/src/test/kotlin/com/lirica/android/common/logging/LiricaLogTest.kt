package com.lirica.android.common.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LiricaLogTest {

    @Before
    fun setUp() {
        LiricaLog.clear()
    }

    @Test
    fun testLogRecording_recordsToRingBuffer() {
        LiricaLog.d("TestTag", "Debug message")
        LiricaLog.i("TestTag", "Info message")
        LiricaLog.w("TestTag", "Warn message", RuntimeException("Warn cause"))
        LiricaLog.e("TestTag", "Error message", IllegalStateException("Error cause"))

        val logs = LiricaLog.getRecentLogs()
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
            LiricaLog.i("Tag", "Message #$i")
        }

        val logs = LiricaLog.getRecentLogs()
        assertEquals(200, logs.size)
        // 应该包含最后写入的 Message #250，而前 50 条被挤出
        assertTrue(logs.last().contains("Message #250"))
        assertTrue(logs.first().contains("Message #51"))
    }
}
