package com.xiaoxi.android.automation.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

interface GestureExecutor {
    suspend fun execute(gesture: GestureSpec, timeoutMs: Long = 3000L): GestureResult
    fun buildPath(gesture: GestureSpec): Path
}

class GestureExecutorImpl(
    private val serviceProvider: () -> AccessibilityService?,
    private val trajectoryCalculator: BezierTrajectoryCalculator = BezierTrajectoryCalculator()
) : GestureExecutor {

    override fun buildPath(gesture: GestureSpec): Path {
        val path = Path()
        when (gesture) {
            is GestureSpec.Click -> {
                path.moveTo(gesture.x, gesture.y)
            }
            is GestureSpec.LongPress -> {
                path.moveTo(gesture.x, gesture.y)
            }
            is GestureSpec.Swipe -> {
                if (gesture.humanoidCurve) {
                    val points = trajectoryCalculator.generateCubicBezierPoints(
                        start = Point2D(gesture.startX, gesture.startY),
                        end = Point2D(gesture.endX, gesture.endY)
                    )
                    if (points.isNotEmpty()) {
                        path.moveTo(points.first().x, points.first().y)
                        for (i in 1 until points.size) {
                            path.lineTo(points[i].x, points[i].y)
                        }
                    } else {
                        path.moveTo(gesture.startX, gesture.startY)
                        path.lineTo(gesture.endX, gesture.endY)
                    }
                } else {
                    path.moveTo(gesture.startX, gesture.startY)
                    path.lineTo(gesture.endX, gesture.endY)
                }
            }
            is GestureSpec.Drag -> {
                path.moveTo(gesture.startX, gesture.startY)
                path.lineTo(gesture.endX, gesture.endY)
            }
        }
        return path
    }

    override suspend fun execute(gesture: GestureSpec, timeoutMs: Long): GestureResult {
        val service = serviceProvider() ?: return GestureResult.ServiceUnavailable

        val path = buildPath(gesture)
        val strokeDuration = when (gesture) {
            is GestureSpec.Click -> gesture.durationMs
            is GestureSpec.LongPress -> gesture.durationMs
            is GestureSpec.Swipe -> gesture.durationMs
            is GestureSpec.Drag -> gesture.holdDurationMs + gesture.dragDurationMs
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, strokeDuration)
        val gestureDescription = GestureDescription.Builder().addStroke(stroke).build()

        return try {
            val result = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<GestureResult> { cont ->
                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(GestureResult.Completed(strokeDuration))
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(GestureResult.Cancelled("System cancelled gesture"))
                        }
                    }

                    val dispatched = service.dispatchGesture(gestureDescription, callback, null)
                    if (!dispatched && cont.isActive) {
                        cont.resume(GestureResult.Cancelled("dispatchGesture returned false"))
                    }
                }
            }
            result ?: GestureResult.TimedOut(timeoutMs)
        } catch (t: Throwable) {
            GestureResult.Error(t)
        }
    }
}
