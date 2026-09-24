package com.xiaoxi.android.common.logging

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Lirica 统一日志门面与内存定长环形缓冲器
 *
 * 既输出到 Android LogCat，又在内存维护最近 200 条系统轨迹，为崩溃报告提供现场上下文。
 */
object XiaoxiLog {

    const val MAX_RING_BUFFER_SIZE = 200
    private val ringBuffer = ConcurrentLinkedDeque<String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    fun d(tag: String, message: String) {
        try {
            Log.d(tag, message)
        } catch (_: Throwable) {
            // Robolectric or pure JVM fallback
        }
        record("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        try {
            Log.i(tag, message)
        } catch (_: Throwable) {
            // Robolectric or pure JVM fallback
        }
        record("INFO", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        try {
            Log.w(tag, message, throwable)
        } catch (_: Throwable) {
            // Robolectric or pure JVM fallback
        }
        val trace = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        record("WARN", tag, "$message$trace")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        try {
            Log.e(tag, message, throwable)
        } catch (_: Throwable) {
            // Robolectric or pure JVM fallback
        }
        val trace = throwable?.let { "\n${it.stackTraceToString()}" } ?: ""
        record("ERROR", tag, "$message$trace")
    }

    private fun record(level: String, tag: String, message: String) {
        val timestamp = synchronized(dateFormat) { dateFormat.format(Date()) }
        val entry = "[$timestamp][$level][$tag] $message"
        ringBuffer.addLast(entry)
        while (ringBuffer.size > MAX_RING_BUFFER_SIZE) {
            ringBuffer.pollFirst()
        }
    }

    /**
     * 获取当前内存中缓存的全部最近日志快照
     */
    fun getRecentLogs(): List<String> = ringBuffer.toList()

    /**
     * 测试用：清理内存缓冲区
     */
    internal fun clear() {
        ringBuffer.clear()
    }
}
