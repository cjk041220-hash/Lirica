package com.xiaoxi.android.automation.grounding

import com.xiaoxi.android.automation.gesture.Point2D
import kotlin.math.max
import kotlin.math.min

data class ElementBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = max(0, right - left)
    val height: Int get() = max(0, bottom - top)
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val area: Int get() = width * height
}

data class UiElementNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val resourceId: String? = null,
    val className: String? = null,
    val bounds: ElementBounds,
    val isClickable: Boolean = true,
    val isVisibleToUser: Boolean = true
)

/**
 * Normalized rectangle in 0..1000 coordinate space.
 */
data class NormalizedRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun centerX(): Float = (left + right) / 2f
    fun centerY(): Float = (top + bottom) / 2f

    fun toScreenPoint(screenWidth: Int, screenHeight: Int): Point2D {
        val safeW = max(1, screenWidth)
        val safeH = max(1, screenHeight)
        val px = (centerX() / 1000f) * safeW
        val py = (centerY() / 1000f) * safeH
        return Point2D(px, py)
    }

    fun iou(other: NormalizedRect): Float {
        val interLeft = max(left, other.left)
        val interTop = max(top, other.top)
        val interRight = min(right, other.right)
        val interBottom = min(bottom, other.bottom)

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        val area1 = max(0f, right - left) * max(0f, bottom - top)
        val area2 = max(0f, other.right - other.left) * max(0f, other.bottom - other.top)
        val unionArea = area1 + area2 - interArea

        return if (unionArea <= 0f) 0f else interArea / unionArea
    }

    companion object {
        fun fromPixelBounds(
            bounds: ElementBounds,
            screenWidth: Int,
            screenHeight: Int
        ): NormalizedRect {
            val safeW = max(1, screenWidth).toFloat()
            val safeH = max(1, screenHeight).toFloat()
            return NormalizedRect(
                left = (bounds.left.toFloat() / safeW) * 1000f,
                top = (bounds.top.toFloat() / safeH) * 1000f,
                right = (bounds.right.toFloat() / safeW) * 1000f,
                bottom = (bounds.bottom.toFloat() / safeH) * 1000f
            )
        }
    }
}

data class GroundingQuery(
    val text: String? = null,
    val resourceId: String? = null,
    val semanticDescription: String? = null
)

enum class GroundingSource {
    ACCESSIBILITY_EXACT,
    ACCESSIBILITY_FUZZY,
    VISUAL_VLM,
    HYBRID_FUSION
}

sealed interface GroundingResult {
    data class Found(
        val targetPoint: Point2D,
        val bounds: NormalizedRect,
        val source: GroundingSource,
        val confidence: Float,
        val refNode: UiElementNode? = null
    ) : GroundingResult

    data class Ambiguous(
        val candidates: List<Found>,
        val message: String
    ) : GroundingResult

    data class NotFound(
        val reason: String
    ) : GroundingResult
}
