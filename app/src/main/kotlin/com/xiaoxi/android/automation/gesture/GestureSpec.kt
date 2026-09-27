package com.xiaoxi.android.automation.gesture

data class Point2D(val x: Float, val y: Float)

sealed interface GestureSpec {
    val targetPackage: String? get() = null
    val targetText: String? get() = null
    val targetResourceId: String? get() = null
    val isPasswordTarget: Boolean get() = false
    val isTextInput: Boolean get() = false

    data class Click(
        val x: Float,
        val y: Float,
        val durationMs: Long = 100L,
        override val targetPackage: String? = null,
        override val targetText: String? = null,
        override val targetResourceId: String? = null,
        override val isPasswordTarget: Boolean = false,
        override val isTextInput: Boolean = false
    ) : GestureSpec

    data class LongPress(
        val x: Float,
        val y: Float,
        val durationMs: Long = 600L,
        override val targetPackage: String? = null,
        override val targetText: String? = null,
        override val targetResourceId: String? = null,
        override val isPasswordTarget: Boolean = false,
        override val isTextInput: Boolean = false
    ) : GestureSpec

    data class Swipe(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val durationMs: Long = 350L,
        val humanoidCurve: Boolean = true,
        override val targetPackage: String? = null,
        override val targetText: String? = null,
        override val targetResourceId: String? = null,
        override val isPasswordTarget: Boolean = false,
        override val isTextInput: Boolean = false
    ) : GestureSpec

    data class Drag(
        val startX: Float,
        val startY: Float,
        val endX: Float,
        val endY: Float,
        val holdDurationMs: Long = 300L,
        val dragDurationMs: Long = 500L,
        override val targetPackage: String? = null,
        override val targetText: String? = null,
        override val targetResourceId: String? = null,
        override val isPasswordTarget: Boolean = false,
        override val isTextInput: Boolean = false
    ) : GestureSpec
}
