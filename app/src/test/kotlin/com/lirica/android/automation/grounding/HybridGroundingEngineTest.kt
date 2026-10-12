package com.lirica.android.automation.grounding

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HybridGroundingEngineTest {

    private lateinit var visualDetector: VisualElementDetector
    private lateinit var engine: HybridGroundingEngineImpl

    private val screenWidth = 1080
    private val screenHeight = 2400

    @Before
    fun setUp() {
        visualDetector = mockk(relaxed = true)
        engine = HybridGroundingEngineImpl(visualDetector)
    }

    @Test
    fun testLocate_exactResourceIdMatch_returnsAccessibilityExact() = runTest {
        val targetNode = UiElementNode(
            text = "Settings",
            resourceId = "com.android.settings:id/title",
            bounds = ElementBounds(100, 200, 300, 400)
        )
        val tree = listOf(
            targetNode,
            UiElementNode(
                text = "Other",
                resourceId = "com.android.settings:id/other",
                bounds = ElementBounds(100, 500, 300, 700)
            )
        )

        val query = GroundingQuery(resourceId = "com.android.settings:id/title")
        val result = engine.locate(query, tree, null, screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Found)
        val found = result as GroundingResult.Found
        assertEquals(GroundingSource.ACCESSIBILITY_EXACT, found.source)
        assertEquals(200f, found.targetPoint.x, 1.0f)
        assertEquals(300f, found.targetPoint.y, 1.0f)
        assertEquals(0.95f, found.confidence, 0.01f)
        assertEquals(targetNode, found.refNode)
    }

    @Test
    fun testLocate_exactTextMatch_returnsAccessibilityExact() = runTest {
        val targetNode = UiElementNode(
            text = "Confirm Payment",
            bounds = ElementBounds(100, 1000, 980, 1150)
        )
        val tree = listOf(targetNode)

        val query = GroundingQuery(text = "Confirm Payment")
        val result = engine.locate(query, tree, null, screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Found)
        val found = result as GroundingResult.Found
        assertEquals(GroundingSource.ACCESSIBILITY_EXACT, found.source)
        assertEquals(540f, found.targetPoint.x, 1.0f)
        assertEquals(1075f, found.targetPoint.y, 1.0f)
    }

    @Test
    fun testLocate_multipleExactMatches_returnsAmbiguous() = runTest {
        val node1 = UiElementNode(text = "Delete", bounds = ElementBounds(100, 100, 200, 200))
        val node2 = UiElementNode(text = "Delete", bounds = ElementBounds(100, 300, 200, 400))
        val tree = listOf(node1, node2)

        val query = GroundingQuery(text = "Delete")
        val result = engine.locate(query, tree, null, screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Ambiguous)
        val ambiguous = result as GroundingResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun testLocate_emptyTree_visualFallback_returnsVisualVlm() = runTest {
        val visualRect = NormalizedRect(100f, 200f, 300f, 400f)
        coEvery { visualDetector.detect(any(), "Shopping Cart") } returns visualRect

        val query = GroundingQuery(semanticDescription = "Shopping Cart")
        val result = engine.locate(query, emptyList(), ByteArray(10), screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Found)
        val found = result as GroundingResult.Found
        assertEquals(GroundingSource.VISUAL_VLM, found.source)
        assertEquals(0.85f, found.confidence, 0.01f)
        assertEquals(visualRect, found.bounds)
    }

    @Test
    fun testLocate_fuzzyTreeWithVisualAlignment_returnsHybridFusion() = runTest {
        // Pixel bounds in screen: 108 to 324 X (100 to 300 in 1000 scale), 480 to 960 Y (200 to 400 in 1000 scale)
        val fuzzyNode = UiElementNode(
            text = "My Shopping Cart (3)",
            bounds = ElementBounds(108, 480, 324, 960)
        )
        val tree = listOf(fuzzyNode)

        val visualRect = NormalizedRect(100f, 200f, 300f, 400f)
        coEvery { visualDetector.detect(any(), "Shopping Cart") } returns visualRect

        val query = GroundingQuery(semanticDescription = "Shopping Cart")
        val result = engine.locate(query, tree, ByteArray(10), screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Found)
        val found = result as GroundingResult.Found
        assertEquals(GroundingSource.HYBRID_FUSION, found.source)
        assertEquals(0.98f, found.confidence, 0.01f)
        assertEquals(fuzzyNode, found.refNode)
    }

    @Test
    fun testLocate_fuzzyOnlyWithoutVisual_returnsAccessibilityFuzzy() = runTest {
        val fuzzyNode = UiElementNode(
            text = "Click to Subscribe Now",
            bounds = ElementBounds(100, 500, 900, 600)
        )
        val tree = listOf(fuzzyNode)

        // Visual detector returns null
        coEvery { visualDetector.detect(any(), any()) } returns null

        val query = GroundingQuery(text = "Subscribe")
        val result = engine.locate(query, tree, null, screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Found)
        val found = result as GroundingResult.Found
        assertEquals(GroundingSource.ACCESSIBILITY_FUZZY, found.source)
        assertEquals(0.75f, found.confidence, 0.01f)
        assertEquals(fuzzyNode, found.refNode)
    }

    @Test
    fun testLocate_multipleFuzzyMatches_returnsAmbiguous() = runTest {
        val fuzzyNode1 = UiElementNode(text = "Item 1 Detail", bounds = ElementBounds(100, 100, 500, 200))
        val fuzzyNode2 = UiElementNode(text = "Item 2 Detail", bounds = ElementBounds(100, 300, 500, 400))
        val tree = listOf(fuzzyNode1, fuzzyNode2)

        coEvery { visualDetector.detect(any(), any()) } returns null

        val query = GroundingQuery(text = "Detail")
        val result = engine.locate(query, tree, null, screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Ambiguous)
        val ambiguous = result as GroundingResult.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
    }

    @Test
    fun testLocate_notFoundWhenBothFail_returnsNotFound() = runTest {
        coEvery { visualDetector.detect(any(), any()) } returns null

        val query = GroundingQuery(text = "Nonexistent Element")
        val result = engine.locate(query, emptyList(), null, screenWidth, screenHeight)

        assertTrue(result is GroundingResult.NotFound)
    }

    @Test
    fun testNormalizedRect_toScreenPoint_clampsSafely() {
        val norm = NormalizedRect(0f, 0f, 1000f, 1000f)
        val point = norm.toScreenPoint(screenWidth, screenHeight)
        assertEquals(540f, point.x, 0.1f)
        assertEquals(1200f, point.y, 0.1f)

        // Negative/zero width handling
        val clamped = norm.toScreenPoint(0, -100)
        assertNotNull(clamped)
    }

    @Test
    fun testNormalizedRect_iou_calculation() {
        val rect1 = NormalizedRect(0f, 0f, 100f, 100f)
        val rect2 = NormalizedRect(50f, 0f, 150f, 100f) // 50x100 overlap, union 150x100
        val iou = rect1.iou(rect2)
        // inter = 5000, union = 10000 + 10000 - 5000 = 15000 -> 5000/15000 = 0.3333f
        assertEquals(0.333f, iou, 0.01f)

        val nonOverlapping = NormalizedRect(200f, 200f, 300f, 300f)
        assertEquals(0f, rect1.iou(nonOverlapping), 0.001f)
    }

    @Test
    fun testLocate_mlKitDetector_returnsVisualOcr() = runTest {
        val mlKitDetector = MlKitVisualDetector(
            ocrEngineRunner = {
                listOf(VisualDetection("立即结算", NormalizedRect(200f, 900f, 800f, 980f)))
            }
        )
        val ocrEngine = HybridGroundingEngineImpl(mlKitDetector)

        val query = GroundingQuery(text = "立即结算")
        val result = ocrEngine.locate(query, emptyList(), byteArrayOf(1, 2), screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Found)
        val found = result as GroundingResult.Found
        assertEquals(GroundingSource.VISUAL_OCR, found.source)
        assertEquals(0.88f, found.confidence, 0.01f)
        assertEquals(200f, found.bounds.left, 0.1f)
        assertEquals(900f, found.bounds.top, 0.1f)
    }

    @Test
    fun testLocate_parentChildOverlappingExactMatches_collapsesToSingleFound() = runTest {
        // 模拟华为设置列表项：外层 LinearLayout (isClickable=true) 与内层 TextView (isClickable=false) 同具"系统和更新"
        val parentContainer = UiElementNode(
            text = "系统和更新",
            bounds = ElementBounds(0, 1000, 1080, 1200),
            isClickable = true
        )
        val childTextView = UiElementNode(
            text = "系统和更新",
            bounds = ElementBounds(60, 1040, 400, 1160),
            isClickable = false
        )
        val tree = listOf(parentContainer, childTextView)

        val query = GroundingQuery(text = "系统和更新")
        val result = engine.locate(query, tree, null, screenWidth, screenHeight)

        // 应空间层叠收拢为可交互的外层容器，而非报错 Ambiguous
        assertTrue(result is GroundingResult.Found)
        val found = result as GroundingResult.Found
        assertEquals(parentContainer, found.refNode)
        assertEquals(0.95f, found.confidence, 0.01f)
    }

    @Test
    fun testLocate_parentChildOverlappingFuzzyMatches_collapsesToSingleFound() = runTest {
        coEvery { visualDetector.detect(any(), any()) } returns null
        // 模拟搜索栏：外层卡片与内层图标均模糊匹配"搜索"
        val searchCard = UiElementNode(
            contentDescription = "搜索设置项",
            bounds = ElementBounds(40, 100, 1040, 220),
            isClickable = true
        )
        val searchIcon = UiElementNode(
            contentDescription = "搜索图标按钮",
            bounds = ElementBounds(60, 120, 140, 200),
            isClickable = false
        )
        val tree = listOf(searchCard, searchIcon)

        val query = GroundingQuery(text = "搜索")
        val result = engine.locate(query, tree, null, screenWidth, screenHeight)

        assertTrue(result is GroundingResult.Found)
        val found = result as GroundingResult.Found
        assertEquals(searchCard, found.refNode)
    }
}
