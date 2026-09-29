package com.lirica.android.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPositionCalculatorTest {

    @Test
    fun testSnapToLeftEdgeWhenOnLeftHalf() {
        val screenWidth = 1080
        val bubbleWidth = 160
        val edgeMargin = 20

        // Center is at 100 + 80 = 180 < 540 (left half)
        val snapX = OverlayPositionCalculator.calculateSnapTargetX(
            currentX = 100,
            bubbleWidth = bubbleWidth,
            screenWidth = screenWidth,
            edgeMargin = edgeMargin
        )
        assertEquals(20, snapX)
    }

    @Test
    fun testSnapToRightEdgeWhenOnRightHalf() {
        val screenWidth = 1080
        val bubbleWidth = 160
        val edgeMargin = 20

        // Center is at 600 + 80 = 680 >= 540 (right half)
        val snapX = OverlayPositionCalculator.calculateSnapTargetX(
            currentX = 600,
            bubbleWidth = bubbleWidth,
            screenWidth = screenWidth,
            edgeMargin = edgeMargin
        )
        val expectedRight = screenWidth - bubbleWidth - edgeMargin // 1080 - 160 - 20 = 900
        assertEquals(expectedRight, snapX)
    }

    @Test
    fun testClampingWithinScreenBounds() {
        val screenWidth = 1080
        val screenHeight = 2400
        val bubbleWidth = 160
        val bubbleHeight = 160
        val topInset = 100
        val bottomInset = 150

        // Case 1: Within bounds
        val clampedNormal = OverlayPositionCalculator.clampToBounds(
            x = 500,
            y = 1000,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topInset = topInset,
            bottomInset = bottomInset
        )
        assertEquals(Pair(500, 1000), clampedNormal)

        // Case 2: Exceeding left & top
        val clampedLeftTop = OverlayPositionCalculator.clampToBounds(
            x = -50,
            y = 20,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topInset = topInset,
            bottomInset = bottomInset
        )
        assertEquals(Pair(0, 100), clampedLeftTop)

        // Case 3: Exceeding right & bottom
        val clampedRightBottom = OverlayPositionCalculator.clampToBounds(
            x = 1200,
            y = 2500,
            bubbleWidth = bubbleWidth,
            bubbleHeight = bubbleHeight,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            topInset = topInset,
            bottomInset = bottomInset
        )
        val maxAllowedX = screenWidth - bubbleWidth // 920
        val maxAllowedY = screenHeight - bubbleHeight - bottomInset // 2400 - 160 - 150 = 2090
        assertEquals(Pair(maxAllowedX, maxAllowedY), clampedRightBottom)
    }

    @Test
    fun testDampedOffsetBeyondBounds() {
        val minBound = 0f
        val maxBound = 1000f
        val dampingRatio = 0.35f

        // Within bounds: no damping
        val within = OverlayPositionCalculator.calculateDampedPosition(
            draggedOffset = 500f,
            minBound = minBound,
            maxBound = maxBound,
            dampingRatio = dampingRatio
        )
        assertEquals(500f, within, 0.001f)

        // Below minBound (-100 beyond min): minBound + (-100 * 0.35) = -35
        val below = OverlayPositionCalculator.calculateDampedPosition(
            draggedOffset = -100f,
            minBound = minBound,
            maxBound = maxBound,
            dampingRatio = dampingRatio
        )
        assertEquals(-35f, below, 0.001f)

        // Above maxBound (1100, 100 beyond max): maxBound + (100 * 0.35) = 1035
        val above = OverlayPositionCalculator.calculateDampedPosition(
            draggedOffset = 1100f,
            minBound = minBound,
            maxBound = maxBound,
            dampingRatio = dampingRatio
        )
        assertEquals(1035f, above, 0.001f)
    }
}
