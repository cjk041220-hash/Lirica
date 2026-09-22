package com.xiaoxi.android.automation.grounding

/**
 * 视觉检测到的文本/元素结果模型
 *
 * @property text 识别出的文本内容
 * @property bounds 在 0..1000 归一化坐标系下的矩形框
 * @property confidence 置信度（0.0 ~ 1.0）
 */
data class VisualDetection(
    val text: String,
    val bounds: NormalizedRect,
    val confidence: Float = 1.0f
)

interface VisualElementDetector {
    /**
     * 在屏幕截图中根据查询词定位目标区域。
     *
     * @param screenshot 屏幕图像数据（JPEG 或 PNG 字节流）
     * @param query 目标语义或关键字
     * @return 0..1000 坐标系下的归一化矩形，未匹配时返回 null
     */
    suspend fun detect(screenshot: ByteArray?, query: String): NormalizedRect?

    /**
     * 识别并返回截图中全部可见的视觉文本/元素候选（全屏 OCR 扫描）。
     *
     * @param screenshot 屏幕图像数据
     * @return 检测到的全部视觉元素列表
     */
    suspend fun detectAll(screenshot: ByteArray?): List<VisualDetection> = emptyList()
}
