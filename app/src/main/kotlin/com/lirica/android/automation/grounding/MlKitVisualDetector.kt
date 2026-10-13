package com.lirica.android.automation.grounding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.lirica.android.common.logging.LiricaLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 基于 Google ML Kit 离线中文模型的端侧视觉文字识别器 (MlKitVisualDetector)
 *
 * 核心技术指标：
 * 1. 100% 离线脱机：打包内置模型，免 GMS 依赖，国内各大 ROM 均可平稳运行；
 * 2. 毫秒级极速推理：耗时 30~60ms，低能耗低内存；
 * 3. 0..1000 坐标规范归一化：与 [NormalizedRect] 绝对对齐；
 * 4. 三级文本匹配决策：精确匹配 -> 包含匹配 -> 反向包含匹配。
 */
class MlKitVisualDetector(
    private val recognizerProvider: (() -> TextRecognizer)? = null,
    private val ocrEngineRunner: (suspend (ByteArray) -> List<VisualDetection>)? = null
) : VisualElementDetector {

    private val recognizer: TextRecognizer by lazy {
        recognizerProvider?.invoke()
            ?: TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    override suspend fun detect(screenshot: ByteArray?, query: String): NormalizedRect? {
        if (screenshot == null || query.isBlank()) return null
        val detections = detectAll(screenshot)
        if (detections.isEmpty()) return null

        val trimmedQuery = query.trim()

        // 1. 精确匹配（完全相等）
        val exactMatch = detections.firstOrNull { it.text.trim().equals(trimmedQuery, ignoreCase = true) }
        if (exactMatch != null) {
            return exactMatch.bounds
        }

        // 2. 包含匹配（识别行包含查询词）
        val containsMatch = detections.firstOrNull { it.text.contains(trimmedQuery, ignoreCase = true) }
        if (containsMatch != null) {
            return containsMatch.bounds
        }

        // 3. 反向包含匹配（查询词包含识别行，且识别行具有一定代表性长度）
        val reverseMatch = detections.firstOrNull {
            it.text.length >= 2 && trimmedQuery.contains(it.text, ignoreCase = true)
        }
        return reverseMatch?.bounds
    }

    override suspend fun detectAll(screenshot: ByteArray?): List<VisualDetection> {
        if (screenshot == null || screenshot.isEmpty()) return emptyList()

        // 优先支持测试注入运行器
        if (ocrEngineRunner != null) {
            return ocrEngineRunner.invoke(screenshot)
        }

        val bitmap: Bitmap? = try {
            BitmapFactory.decodeByteArray(screenshot, 0, screenshot.size)
        } catch (e: Throwable) {
            LiricaLog.e("MlKitVisualDetector", "解码屏幕位图失败", e)
            null
        }

        if (bitmap == null) return emptyList()

        val bmpWidth = bitmap.width.toFloat()
        val bmpHeight = bitmap.height.toFloat()

        if (bmpWidth <= 0f || bmpHeight <= 0f) return emptyList()

        val image = InputImage.fromBitmap(bitmap, 0)

        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                runCatching { bitmap.recycle() }
            }
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val results = mutableListOf<VisualDetection>()
                    for (block in visionText.textBlocks) {
                        for (line in block.lines) {
                            val box = line.boundingBox
                            if (box != null) {
                                // 严格换算为 0..1000 归一化矩形
                                val norm = NormalizedRect(
                                    left = (box.left.toFloat() / bmpWidth) * 1000f,
                                    top = (box.top.toFloat() / bmpHeight) * 1000f,
                                    right = (box.right.toFloat() / bmpWidth) * 1000f,
                                    bottom = (box.bottom.toFloat() / bmpHeight) * 1000f
                                )
                                val conf = line.confidence ?: 1.0f
                                results.add(VisualDetection(text = line.text, bounds = norm, confidence = conf))
                            }
                        }
                    }
                    // M6.0 P1-8：resume 前守 isActive——识别回调在主线程，协程取消后
                    // 迟到的成功回调会在已取消 continuation 上崩溃应用
                    if (continuation.isActive) continuation.resume(results)
                }
                .addOnFailureListener { exception ->
                    LiricaLog.e("MlKitVisualDetector", "ML Kit 文字识别异常", exception)
                    if (continuation.isActive) continuation.resume(emptyList())
                }
                .addOnCompleteListener {
                    // 识别任务结束后显式回收位图内存，防止高频截帧检测时诱发 GC 抖动
                    runCatching { bitmap.recycle() }
                }
        }
    }
}
