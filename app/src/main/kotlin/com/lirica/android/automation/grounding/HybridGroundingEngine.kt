package com.lirica.android.automation.grounding

import kotlin.math.sqrt

interface HybridGroundingEngine {
    suspend fun locate(
        query: GroundingQuery,
        tree: List<UiElementNode>?,
        screenshot: ByteArray?,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingResult
}

class HybridGroundingEngineImpl(
    private val visualDetector: VisualElementDetector? = null
) : HybridGroundingEngine {

    override suspend fun locate(
        query: GroundingQuery,
        tree: List<UiElementNode>?,
        screenshot: ByteArray?,
        screenWidth: Int,
        screenHeight: Int
    ): GroundingResult {
        // 屏幕尺寸未知（<=0）时直接返回 NotFound，绝不用 1080x2400 兜底：
        // 兜底会让归一化坐标按错误比例换算，产生"看起来成功、实际点错位置"的静默故障；
        // 明确失败可交由上层自愈链路（重新抓树 / 重试）或日志暴露装配问题。
        if (screenWidth <= 0 || screenHeight <= 0) {
            return GroundingResult.NotFound(
                "Screen size unavailable (w=$screenWidth, h=$screenHeight): grounding requires real display metrics"
            )
        }
        val safeW = screenWidth
        val safeH = screenHeight

        val visibleNodes = tree?.filter { it.isVisibleToUser } ?: emptyList()

        val exactMatches = mutableListOf<UiElementNode>()
        val fuzzyMatches = mutableListOf<UiElementNode>()

        for (node in visibleNodes) {
            // Check exact resource ID
            if (!query.resourceId.isNullOrBlank() && node.resourceId == query.resourceId) {
                exactMatches.add(node)
                continue
            }

            val nodeText = node.text ?: ""
            val nodeDesc = node.contentDescription ?: ""

            // Check exact text/description match
            if (!query.text.isNullOrBlank()) {
                if (nodeText == query.text || nodeDesc == query.text) {
                    exactMatches.add(node)
                    continue
                } else if (nodeText.contains(query.text, ignoreCase = true) || nodeDesc.contains(query.text, ignoreCase = true)) {
                    fuzzyMatches.add(node)
                    continue
                }
            }

            // Check semantic description in text/desc
            if (!query.semanticDescription.isNullOrBlank()) {
                if (nodeText.contains(query.semanticDescription, ignoreCase = true) || nodeDesc.contains(query.semanticDescription, ignoreCase = true)) {
                    fuzzyMatches.add(node)
                }
            }
        }

        // Multiple exact matches -> Ambiguous
        if (exactMatches.size > 1) {
            val candidates = exactMatches.map { node ->
                val normRect = NormalizedRect.fromPixelBounds(node.bounds, safeW, safeH)
                GroundingResult.Found(normRect.toScreenPoint(safeW, safeH), normRect, GroundingSource.ACCESSIBILITY_EXACT, 0.95f, node)
            }
            return GroundingResult.Ambiguous(candidates, "Found ${exactMatches.size} identical exact matches in accessibility tree")
        }

        // Single exact match -> L0 Fast Path
        if (exactMatches.size == 1) {
            val node = exactMatches.first()
            val normRect = NormalizedRect.fromPixelBounds(node.bounds, safeW, safeH)
            return GroundingResult.Found(
                targetPoint = normRect.toScreenPoint(safeW, safeH),
                bounds = normRect,
                source = GroundingSource.ACCESSIBILITY_EXACT,
                confidence = 0.95f,
                refNode = node
            )
        }

        // Step 2: Visual Grounding if visualDetector available
        val visualQuery = query.semanticDescription ?: query.text ?: query.resourceId ?: ""
        val visualRect = if (visualDetector != null && visualQuery.isNotBlank()) {
            visualDetector.detect(screenshot, visualQuery)
        } else null

        // Step 3: Fusion or Fallback
        if (visualRect != null) {
            // Check if aligns with any fuzzy match
            if (fuzzyMatches.isNotEmpty()) {
                for (fuzzy in fuzzyMatches) {
                    val fuzzyNorm = NormalizedRect.fromPixelBounds(fuzzy.bounds, safeW, safeH)
                    if (visualRect.iou(fuzzyNorm) > 0.3f || distance(visualRect.centerX(), visualRect.centerY(), fuzzyNorm.centerX(), fuzzyNorm.centerY()) < 150f) {
                        return GroundingResult.Found(
                            targetPoint = visualRect.toScreenPoint(safeW, safeH),
                            bounds = visualRect,
                            source = GroundingSource.HYBRID_FUSION,
                            confidence = 0.98f,
                            refNode = fuzzy
                        )
                    }
                }
            }

            // Visual only match
            val visualSource = if (visualDetector is MlKitVisualDetector) {
                GroundingSource.VISUAL_OCR
            } else {
                GroundingSource.VISUAL_VLM
            }
            return GroundingResult.Found(
                targetPoint = visualRect.toScreenPoint(safeW, safeH),
                bounds = visualRect,
                source = visualSource,
                confidence = if (visualSource == GroundingSource.VISUAL_OCR) 0.88f else 0.85f
            )
        }

        // Single fuzzy match fallback if no visual
        if (fuzzyMatches.size == 1) {
            val node = fuzzyMatches.first()
            val normRect = NormalizedRect.fromPixelBounds(node.bounds, safeW, safeH)
            return GroundingResult.Found(
                targetPoint = normRect.toScreenPoint(safeW, safeH),
                bounds = normRect,
                source = GroundingSource.ACCESSIBILITY_FUZZY,
                confidence = 0.75f,
                refNode = node
            )
        } else if (fuzzyMatches.size > 1) {
            val candidates = fuzzyMatches.map { node ->
                val normRect = NormalizedRect.fromPixelBounds(node.bounds, safeW, safeH)
                GroundingResult.Found(normRect.toScreenPoint(safeW, safeH), normRect, GroundingSource.ACCESSIBILITY_FUZZY, 0.75f, node)
            }
            return GroundingResult.Ambiguous(candidates, "Found ${fuzzyMatches.size} fuzzy matches in accessibility tree")
        }

        return GroundingResult.NotFound("Target could not be located in accessibility tree or visual observation")
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }
}
