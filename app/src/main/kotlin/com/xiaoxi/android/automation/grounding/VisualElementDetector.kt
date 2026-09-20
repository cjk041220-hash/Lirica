package com.xiaoxi.android.automation.grounding

interface VisualElementDetector {
    suspend fun detect(screenshot: ByteArray?, query: String): NormalizedRect?
}
