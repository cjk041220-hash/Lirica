package com.xiaoxi.android.perception

/**
 * 专为无障碍 Binder 线程定制的定长环形缓冲队列（基于内置监视器锁 @Synchronized 实现轻量线程安全）
 * 入队极简拷贝，耗时 <= 0.05ms，满容量时自动覆盖最旧数据，保证 Binder 线程极速返回
 */
class AccessibilityRingBuffer<T>(val capacity: Int = 128) {

    private val buffer = arrayOfNulls<Any>(capacity)
    private var head = 0
    private var tail = 0
    private var count = 0

    val size: Int
        @Synchronized get() = count

    val isEmpty: Boolean
        @Synchronized get() = count == 0

    val isFull: Boolean
        @Synchronized get() = count == capacity

    @Synchronized
    fun offer(element: T): Boolean {
        if (element == null) return false
        if (count == capacity) {
            // 队列已满，覆盖最旧元素 (移动 head)
            head = (head + 1) % capacity
            count--
        }
        buffer[tail] = element
        tail = (tail + 1) % capacity
        count++
        return true
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun poll(): T? {
        if (count == 0) return null
        val item = buffer[head] as T?
        buffer[head] = null
        head = (head + 1) % capacity
        count--
        return item
    }

    @Synchronized
    fun clear() {
        buffer.fill(null)
        head = 0
        tail = 0
        count = 0
    }
}
