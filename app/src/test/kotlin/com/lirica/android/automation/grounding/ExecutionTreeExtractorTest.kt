package com.lirica.android.automation.grounding

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.lirica.android.perception.PrivacyBreaker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class ExecutionTreeExtractorTest {

    private lateinit var extractor: ExecutionTreeExtractor

    @Before
    fun setUp() {
        extractor = ExecutionTreeExtractor(
            privacyBreaker = PrivacyBreaker(),
            retryDelayMs = 10L // 测试中压缩重试等待
        )
    }

    @Test
    fun testDumpTree_nullRoot_returnsRootNodeNull() = runTest {
        val result = extractor.dumpTree(null, 1080, 2400)
        assertTrue(result is TreeDumpResult.Empty)
        assertEquals(EmptyReason.ROOT_NODE_NULL, (result as TreeDumpResult.Empty).reason)
    }

    @Test
    fun testDumpTree_privacySensitiveApp_triggersBlackout() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        val result = extractor.dumpTree(root, 1080, 2400, packageName = "com.eg.android.AlipayGphone")
        assertTrue(result is TreeDumpResult.Empty)
        assertEquals(EmptyReason.PRIVACY_BLACKOUT, (result as TreeDumpResult.Empty).reason)
    }

    @Test
    fun testDumpTree_normalNodes_extractsCoordinatesAndPriority() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 2
        every { root.packageName } returns "com.example.app"

        // 子节点 1: 输入框 (priority = 5)
        val editChild = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { editChild.isEditable } returns true
        every { editChild.text } returns "搜索商品"
        every { editChild.viewIdResourceName } returns "com.example:id/edit_search"
        every { editChild.childCount } returns 0
        every { editChild.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(100, 100, 900, 200)
        }

        // 子节点 2: 按钮 (priority = 5)
        val btnChild = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { btnChild.isClickable } returns true
        every { btnChild.text } returns "搜索"
        every { btnChild.childCount } returns 0
        every { btnChild.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(900, 100, 1000, 200)
        }

        every { root.getChild(0) } returns editChild
        every { root.getChild(1) } returns btnChild

        val result = extractor.dumpTree(root, 1080, 2400, packageName = "com.example.app")

        assertTrue(result is TreeDumpResult.Ok)
        val ok = result as TreeDumpResult.Ok
        assertEquals(3, ok.nodes.size) // root + 2 children
        assertFalse(ok.isRecoveredFromRetry)

        val editNode = ok.nodes.find { it.resourceId == "com.example:id/edit_search" }
        assertEquals(5, editNode?.priority)
        assertEquals(100, editNode?.bounds?.left)
        assertEquals(900, editNode?.bounds?.right)
    }

    @Test
    fun testDumpTree_largeScrollableContainer_markedAsLargeContainer() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { root.childCount } returns 1

        // 占屏幕 80% 面积且可滚动的 RecyclerView
        val scrollContainer = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { scrollContainer.isScrollable } returns true
        every { scrollContainer.childCount } returns 0
        every { scrollContainer.getBoundsInScreen(any()) } answers {
            firstArg<Rect>().set(0, 200, 1080, 2200) // 面积 1080 * 2000 > 1080 * 2400 * 0.5
        }

        every { root.getChild(0) } returns scrollContainer

        val result = extractor.dumpTree(root, 1080, 2400)
        assertTrue(result is TreeDumpResult.Ok)
        val containerNode = (result as TreeDumpResult.Ok).nodes.find { it.bounds.height == 2000 }
        assertTrue(containerNode?.isLargeContainer == true)
        assertEquals(1, containerNode?.priority)
    }

    @Test
    fun testDumpTree_twoRoundRetryRecovery() = runTest {
        val root = mockk<AccessibilityNodeInfo>(relaxed = true)

        // 第一轮：0 个子节点
        // 第二轮：返回 2 个有效按钮
        var callCount = 0
        every { root.childCount } answers {
            callCount++
            if (callCount <= 1) 0 else 2
        }

        val child1 = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { child1.isClickable } returns true
        every { child1.text } returns "按钮1"
        every { child1.childCount } returns 0

        val child2 = mockk<AccessibilityNodeInfo>(relaxed = true)
        every { child2.isClickable } returns true
        every { child2.text } returns "按钮2"
        every { child2.childCount } returns 0

        every { root.getChild(0) } returns child1
        every { root.getChild(1) } returns child2

        val result = extractor.dumpTree(root, 1080, 2400)
        assertTrue(result is TreeDumpResult.Ok)
        val ok = result as TreeDumpResult.Ok
        assertTrue(ok.isRecoveredFromRetry)
        assertEquals(3, ok.nodes.size) // root + 2 children
    }
}
