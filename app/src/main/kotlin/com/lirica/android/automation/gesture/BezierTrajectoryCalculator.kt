package com.lirica.android.automation.gesture

import kotlin.math.sqrt

class BezierTrajectoryCalculator {

    /**
     * Generates a sequence of points along a cubic Bézier curve between [start] and [end].
     * Control points are offset perpendicularly based on [maxDeviationRatio] to simulate humanoid swipe curvature.
     */
    fun generateCubicBezierPoints(
        start: Point2D,
        end: Point2D,
        steps: Int = 20,
        maxDeviationRatio: Float = 0.1f,
        noiseOffset: Float = 0.0f
    ): List<Point2D> {
        val dx = end.x - start.x
        val dy = end.y - start.y
        val distance = sqrt(dx * dx + dy * dy)

        if (distance < 1f || steps <= 1) {
            return listOf(start, end)
        }

        // Unit normal vector perpendicular to (dx, dy)
        val nx = -dy / distance
        val ny = dx / distance

        val deviation = distance * maxDeviationRatio

        // First control point P1 at ~33% distance with positive perpendicular deviation
        val p1 = Point2D(
            x = start.x + dx * 0.33f + nx * deviation + noiseOffset,
            y = start.y + dy * 0.33f + ny * deviation + noiseOffset
        )

        // Second control point P2 at ~66% distance with negative half deviation
        val p2 = Point2D(
            x = start.x + dx * 0.66f - nx * (deviation * 0.5f) + noiseOffset,
            y = start.y + dy * 0.66f - ny * (deviation * 0.5f) + noiseOffset
        )

        val points = ArrayList<Point2D>(steps + 1)
        for (i in 0..steps) {
            val t = i.toFloat() / steps.toFloat()
            val u = 1f - t
            val tt = t * t
            val uu = u * u
            val uuu = uu * u
            val ttt = tt * t

            val x = uuu * start.x + 3f * uu * t * p1.x + 3f * u * tt * p2.x + ttt * end.x
            val y = uuu * start.y + 3f * uu * t * p1.y + 3f * u * tt * p2.y + ttt * end.y
            points.add(Point2D(x, y))
        }

        return points
    }
}
