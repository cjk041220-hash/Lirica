package com.lirica.android.automation.gesture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BezierTrajectoryCalculatorTest {

    private val calculator = BezierTrajectoryCalculator()

    @Test
    fun testGenerateCubicBezierPoints_startAndEndExact() {
        val start = Point2D(100f, 200f)
        val end = Point2D(500f, 800f)

        val points = calculator.generateCubicBezierPoints(start, end, steps = 10)

        assertEquals(11, points.size)
        assertEquals(start.x, points.first().x, 0.01f)
        assertEquals(start.y, points.first().y, 0.01f)
        assertEquals(end.x, points.last().x, 0.01f)
        assertEquals(end.y, points.last().y, 0.01f)
    }

    @Test
    fun testGenerateCubicBezierPoints_zeroDistance_returnsStartAndEnd() {
        val start = Point2D(100f, 100f)
        val end = Point2D(100.5f, 100f) // distance < 1f

        val points = calculator.generateCubicBezierPoints(start, end, steps = 10)

        assertEquals(2, points.size)
        assertEquals(start, points[0])
        assertEquals(end, points[1])
    }

    @Test
    fun testGenerateCubicBezierPoints_hasHumanoidCurvature() {
        val start = Point2D(0f, 0f)
        val end = Point2D(1000f, 0f) // Pure horizontal move

        val points = calculator.generateCubicBezierPoints(start, end, steps = 20, maxDeviationRatio = 0.15f)

        // On a pure horizontal line, normal is vertical, so intermediate points must deviate in Y
        val maxDeviationY = points.maxOf { abs(it.y) }
        assertTrue("Expected non-zero curve deviation in Y, got $maxDeviationY", maxDeviationY > 5f)
    }

    @Test
    fun testGenerateCubicBezierPoints_stepsEdgeCases() {
        val start = Point2D(0f, 0f)
        val end = Point2D(100f, 100f)

        val points1Step = calculator.generateCubicBezierPoints(start, end, steps = 1)
        assertEquals(2, points1Step.size)
    }
}
