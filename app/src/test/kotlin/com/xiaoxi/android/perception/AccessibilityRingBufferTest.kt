package com.xiaoxi.android.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AccessibilityRingBufferTest {

    private lateinit var ringBuffer: AccessibilityRingBuffer<String>

    @Before
    fun setUp() {
        ringBuffer = AccessibilityRingBuffer(capacity = 8)
    }

    @Test
    fun testOfferAndPollFIFO() {
        assertTrue(ringBuffer.isEmpty)
        assertEquals(0, ringBuffer.size)

        assertTrue(ringBuffer.offer("Event-1"))
        assertTrue(ringBuffer.offer("Event-2"))
        assertTrue(ringBuffer.offer("Event-3"))

        assertEquals(3, ringBuffer.size)
        assertFalse(ringBuffer.isEmpty)

        assertEquals("Event-1", ringBuffer.poll())
        assertEquals("Event-2", ringBuffer.poll())
        assertEquals("Event-3", ringBuffer.poll())
        assertNull(ringBuffer.poll())
        assertTrue(ringBuffer.isEmpty)
    }

    @Test
    fun testRingBufferOverflow_dropsOldest() {
        val capacity = 4
        val buffer = AccessibilityRingBuffer<Int>(capacity = capacity)

        // Fill capacity
        for (i in 1..4) {
            assertTrue(buffer.offer(i))
        }
        assertEquals(4, buffer.size)

        // Push 5th element -> Should overwrite oldest (1)
        buffer.offer(5)
        assertEquals(4, buffer.size)

        // Polling should yield 2, 3, 4, 5
        assertEquals(2, buffer.poll())
        assertEquals(3, buffer.poll())
        assertEquals(4, buffer.poll())
        assertEquals(5, buffer.poll())
        assertNull(buffer.poll())
    }

    @Test
    fun testConcurrentProducerConsumer() {
        val count = 10_000
        val buffer = AccessibilityRingBuffer<Int>(capacity = 128)
        val latch = CountDownLatch(2)
        val consumedCount = AtomicInteger(0)

        // Producer thread
        val producer = Thread {
            for (i in 1..count) {
                buffer.offer(i)
            }
            latch.countDown()
        }

        // Consumer thread
        val consumer = Thread {
            while (producer.isAlive || !buffer.isEmpty) {
                val item = buffer.poll()
                if (item != null) {
                    consumedCount.incrementAndGet()
                } else {
                    Thread.yield()
                }
            }
            latch.countDown()
        }

        producer.start()
        consumer.start()

        val finished = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Concurrent producer consumer completed within timeout", finished)
        assertTrue("Consumed elements > 0", consumedCount.get() > 0)
    }
}
