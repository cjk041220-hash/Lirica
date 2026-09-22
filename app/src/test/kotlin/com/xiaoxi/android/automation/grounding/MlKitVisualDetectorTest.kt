package com.xiaoxi.android.automation.grounding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitVisualDetectorTest {

    @Test
    fun testDetect_nullOrEmptyScreenshot_returnsNull() = runTest {
        val detector = MlKitVisualDetector()
        assertNull(detector.detect(null, "登录"))
        assertNull(detector.detect(ByteArray(0), "登录"))
    }

    @Test
    fun testDetect_blankQuery_returnsNull() = runTest {
        val detector = MlKitVisualDetector()
        assertNull(detector.detect(byteArrayOf(1, 2), ""))
        assertNull(detector.detect(byteArrayOf(1, 2), "   "))
    }

    @Test
    fun testDetect_exactMatch_returnsNormalizedBounds() = runTest {
        val mockDetections = listOf(
            VisualDetection(
                text = "登录",
                bounds = NormalizedRect(100f, 200f, 300f, 250f),
                confidence = 0.99f
            ),
            VisualDetection(
                text = "注册新账号",
                bounds = NormalizedRect(100f, 300f, 300f, 350f),
                confidence = 0.95f
            )
        )

        val detector = MlKitVisualDetector(
            ocrEngineRunner = { mockDetections }
        )

        val result = detector.detect(byteArrayOf(1, 2, 3), "登录")
        assertNotNull(result)
        assertEquals(100f, result?.left ?: 0f, 0.01f)
        assertEquals(200f, result?.top ?: 0f, 0.01f)
        assertEquals(300f, result?.right ?: 0f, 0.01f)
        assertEquals(250f, result?.bottom ?: 0f, 0.01f)
    }

    @Test
    fun testDetect_containsMatch_returnsNormalizedBounds() = runTest {
        val mockDetections = listOf(
            VisualDetection(
                text = "立即同意并继续",
                bounds = NormalizedRect(200f, 800f, 800f, 880f),
                confidence = 0.92f
            )
        )

        val detector = MlKitVisualDetector(
            ocrEngineRunner = { mockDetections }
        )

        // 查询子串 "同意" 应该能匹配到 "立即同意并继续"
        val result = detector.detect(byteArrayOf(1, 2, 3), "同意")
        assertNotNull(result)
        assertEquals(200f, result?.left ?: 0f, 0.01f)
        assertEquals(800f, result?.top ?: 0f, 0.01f)
    }

    @Test
    fun testDetect_reverseContainsMatch_returnsNormalizedBounds() = runTest {
        val mockDetections = listOf(
            VisualDetection(
                text = "付款",
                bounds = NormalizedRect(400f, 500f, 600f, 550f),
                confidence = 0.96f
            )
        )

        val detector = MlKitVisualDetector(
            ocrEngineRunner = { mockDetections }
        )

        // 查询词 "点击立即付款按钮" 包含识别行 "付款"
        val result = detector.detect(byteArrayOf(1, 2, 3), "点击立即付款按钮")
        assertNotNull(result)
        assertEquals(400f, result?.left ?: 0f, 0.01f)
    }

    @Test
    fun testDetect_noMatch_returnsNull() = runTest {
        val mockDetections = listOf(
            VisualDetection(
                text = "首页",
                bounds = NormalizedRect(0f, 0f, 100f, 50f)
            )
        )

        val detector = MlKitVisualDetector(
            ocrEngineRunner = { mockDetections }
        )

        val result = detector.detect(byteArrayOf(1, 2, 3), "个人中心")
        assertNull(result)
    }

    @Test
    fun testDetectAll_returnsFullVisualList() = runTest {
        val mockDetections = listOf(
            VisualDetection("微信", NormalizedRect(50f, 50f, 200f, 100f)),
            VisualDetection("通讯录", NormalizedRect(250f, 50f, 400f, 100f)),
            VisualDetection("发现", NormalizedRect(450f, 50f, 600f, 100f)),
            VisualDetection("我", NormalizedRect(650f, 50f, 800f, 100f))
        )

        val detector = MlKitVisualDetector(
            ocrEngineRunner = { mockDetections }
        )

        val all = detector.detectAll(byteArrayOf(1))
        assertEquals(4, all.size)
        assertEquals("微信", all[0].text)
        assertEquals("我", all[3].text)
    }
}
