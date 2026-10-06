package com.lirica.android.automation.grounding

import com.lirica.android.automation.gesture.Point2D
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
    val semanticDescription: String? = null,
    /**
     * 观测层给出的归一化锚点（[0,1000] 比例空间，M5.2 动态闭环专用）。
     *
     * 为什么需要它：单步 ReAct 闭环的决策层用 refId 指代目标，而 refId 背后
     * 可能是"无文本、无 contentDescription 的图标按钮"——纯语义接地对此无能为力，
     * 只能退回云端 VLM 截图，代价最高且违背 API/语义优先公理。
     * 锚点来自**同一轮观测**，因此是当轮最新事实。
     *
     * 优先级裁决：语义匹配（精确/模糊）永远优先于锚点——语义能扛布局改版，
     * 锚点不能；锚点只在①语义歧义消解 ②语义完全找不到时兜底。
     */
    val normalizedAnchorX: Int? = null,
    val normalizedAnchorY: Int? = null
) {
    /** 锚点是否完整可用（两点齐全且在 [0,1000] 空间内） */
    val hasNormalizedAnchor: Boolean
        get() = normalizedAnchorX != null && normalizedAnchorY != null &&
            normalizedAnchorX in 0..1000 && normalizedAnchorY in 0..1000
}

enum class GroundingSource {
    ACCESSIBILITY_EXACT,
    ACCESSIBILITY_FUZZY,
    VISUAL_VLM,
    VISUAL_OCR,
    HYBRID_FUSION,

    /**
     * 归一化锚点接地（M5.2 动态闭环）：语义无法定位时，使用同轮观测给出的坐标落点。
     *
     * 置信度刻意给低（0.60）：它没有语义背书，靠的是"刚刚看到它在那儿"。
     * 单列一个来源而不是复用 ACCESSIBILITY_FUZZY，是为了让轨迹与指标能区分
     * "语义接地成功率"与"坐标兜底率"——这两个数混在一起就看不出接地质量在退化。
     */
    NORMALIZED_ANCHOR
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
