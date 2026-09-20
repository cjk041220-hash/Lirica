package com.xiaoxi.android.automation.gesture

data class Point2D(val x: Float, val y: Float)

sealed interface GestureSpec {
    data class Click(
        val x: Float,
        val y: Float,
        val durationMs: Long = 100L
    ) : GestureSpec

    data class LongPress(
        val x: Float,
        val y: Float,
        val durationMs: Long = 600L
    ) : GestureSpec

    data class Swipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long = 350L,
        val humanoidCurve: Boolean = true
    ) : GestureSpec

    data class Drag(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val holdDurationMs: Long = 300L,
        val dragDurationMs: Long = 500L
    ) : GestureSpec
}
