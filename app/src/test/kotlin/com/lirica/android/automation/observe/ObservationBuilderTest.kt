package com.lirica.android.automation.observe

import com.lirica.android.automation.grounding.ElementBounds
import com.lirica.android.automation.grounding.ExecutionNode
import com.lirica.android.automation.grounding.ExecutionTreeExtractor
import com.lirica.android.automation.grounding.TreeDumpResult
import com.lirica.android.automation.verify.ActionExecutionBudget
import com.lirica.android.automation.verify.BudgetLedger
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多级观测表征回归（M5.0 §5.1.1 · P2-1）。
 *
 * 断言重点全在**硬预算**上：<=30 项、<=8KB、VLM <=5 次。
 * 这三条是方案写死的数字，也是"Prompt 被挤爆/账单失控"的唯一防线。
 */
class ObservationBuilderTest {

    private val extractor = mockk<ExecutionTreeExtractor>()

    private fun node(
        index: Int,
        text: String?,
        clickable: Boolean = true,
        priority: Int = 5,
        top: Int = index * 100
    ) = ExecutionNode(
        nodeId = "n" + index,
        text = text,
        contentDescription = null,
        resourceId = "com.example:id/node" + index,
        className = "android.widget.Button",
        bounds = ElementBounds(0, top, 500, top + 80),
        isClickable = clickable,
        isEditable = false,
        isCheckable = false,
        isChecked = false,
        isScrollable = false,
        isEnabled = true,
        isVisibleToUser = true,
        isPassword = false,
        priority = priority
    )

    private fun okDump(nodes: List<ExecutionNode>) = TreeDumpResult.Ok(
        packageName = "com.example.app",
        activityName = "MainActivity",
        nodes = nodes,
        totalDiscovered = nodes.size,
        isTruncated = false,
        omittedCount = 0,
        dumpDurationMs = 5L
    )

    private fun ocrDetection(text: String, cx: Int, cy: Int) =
        com.lirica.android.automation.grounding.VisualDetection(
            text = text,
            bounds = com.lirica.android.automation.grounding.NormalizedRect(
                left = (cx - 50).toFloat(),
                top = (cy - 20).toFloat(),
                right = (cx + 50).toFloat(),
                bottom = (cy + 20).toFloat()
            )
        )

    private fun builder(
        ocr: (suspend () -> List<com.lirica.android.automation.grounding.VisualDetection>)? = null,
        vlm: (suspend (String, String) -> String?)? = null,
        screenWidth: Int = 1000,
        screenHeight: Int = 2000,
        visionAllowed: Boolean = true
    ) = ObservationBuilder(
        treeExtractor = extractor,
        rootNodeProvider = { null },
        screenWidthProvider = { screenWidth },
        screenHeightProvider = { screenHeight },
        packageProvider = { "com.example.app" },
        localOcrProvider = ocr,
        cloudVlmProvider = vlm,
        timeProvider = { 1_000L },
        visionAllowedProvider = { visionAllowed }
    )

    private fun ledger(budget: ActionExecutionBudget = ActionExecutionBudget.DEFAULT): BudgetLedger =
        BudgetLedger(budget) { 0L }.also { it.start(0L) }

    @Test
    fun testLevel0BuildsCompactedSnapshot() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            okDump(listOf(node(0, "搜索"), node(1, "设置")))

        val result = builder().observe(ledger(), "打开设置")
        assertTrue(result is ObservationResult.Success)
        val snapshot = (result as ObservationResult.Success).snapshot
        assertEquals(ObservationLevel.SEMANTIC_TREE, snapshot.level)
        assertEquals(2, snapshot.nodeCount)
        assertTrue(snapshot.promptText.contains("搜索"))
        assertTrue(snapshot.promptText.startsWith("pkg=com.example.app"))
    }

    @Test
    fun testLevel0CapsCandidateNodesAtBudget() = runTest {
        val many = (0 until 100).map { node(it, "按钮" + it) }
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns okDump(many)

        val result = builder().observe(ledger(), "遍历列表") as ObservationResult.Success
        assertEquals("候选节点必须被裁剪到 30 项（§5.1）", 30, result.snapshot.nodeCount)
        assertTrue("被裁剪的数量必须诚实披露", result.snapshot.truncatedCount > 0)
    }

    @Test
    fun testPromptRespectsCharBudget() = runTest {
        val longText = "很长的按钮文本".repeat(30)
        val many = (0 until 100).map { node(it, longText) }
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns okDump(many)

        val result = builder().observe(ledger(), "遍历") as ObservationResult.Success
        assertTrue(
            "Prompt 字符数 " + result.snapshot.promptCharCount + " 不得超过 8KB 预算",
            result.snapshot.promptCharCount <= ActionExecutionBudget.DEFAULT.maxObservationChars
        )
    }

    @Test
    fun testCoordinatesAreNormalized() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            okDump(listOf(node(0, "确定", top = 0), node(1, "取消", top = 1900)))

        val result = builder().observe(ledger(), "选择") as ObservationResult.Success
        val first = result.snapshot.nodes[0]
        // 屏幕 1000x2000；第一个节点中心 y=40 → 归一化 20
        assertEquals(250, first.normalizedX)
        assertEquals(20, first.normalizedY)
        assertTrue("坐标必须落在 [0,1000] 空间内", result.snapshot.nodes.all { it.normalizedY in 0..1000 })
    }

    @Test
    fun testLevel1OcrFallbackWhenTreeIsEmpty() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            TreeDumpResult.Empty(
                reason = com.lirica.android.automation.grounding.EmptyReason.ROOT_NODE_NULL,
                message = "根节点为空"
            )

        val result = builder(ocr = {
            listOf(
                ocrDetection("播放", 200, 400),
                ocrDetection("收藏", 500, 800),
                ocrDetection("分享", 800, 1200)
            )
        }).observe(ledger(), "看视频")
        assertTrue(result is ObservationResult.Success)
        val snapshot = (result as ObservationResult.Success).snapshot
        assertEquals(ObservationLevel.LOCAL_OCR, snapshot.level)
        assertEquals(3, snapshot.nodeCount)
    }

    @Test
    fun testOcrNodesCarryRealBounds_notScreenCenter() {
        // M6.0 B12：历史实现把 OCR 节点坐标恒填 (500,500)——自绘 Canvas 场景下
        // 决策层点任一 OCR ref 都会落到屏幕正中心。契约改为携带真实 bounds 后
        // 锚点必须反映检测框中心。
        runTest {
            coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
                TreeDumpResult.Empty(com.lirica.android.automation.grounding.EmptyReason.ROOT_NODE_NULL, "空")
            val result = builder(ocr = { listOf(ocrDetection("开始游戏", 800, 300)) })
                .observe(ledger(), "开始游戏") as ObservationResult.Success
            val node = result.snapshot.nodes.first()
            assertEquals(800, node.normalizedX)
            assertEquals(300, node.normalizedY)
            // OCR 文本未被证实可点击，isClickable 保持诚实 false
            assertEquals(false, node.isClickable)
        }
    }

    @Test
    fun testLevel2VlmFallbackConsumesBudget() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            TreeDumpResult.Empty(com.lirica.android.automation.grounding.EmptyReason.ROOT_NODE_NULL, "空")
        val budgetLedger = ledger()
        val result = builder(vlm = { _, _ -> "屏幕中央有一个播放按钮" }).observe(budgetLedger, "播放")

        assertTrue(result is ObservationResult.Success)
        assertEquals(ObservationLevel.CLOUD_VLM, (result as ObservationResult.Success).snapshot.level)
        assertEquals("VLM 调用必须计入预算账本", 1, budgetLedger.vlmCalls)
    }

    @Test
    fun testVlmHardCapAtFiveCalls() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            TreeDumpResult.Empty(com.lirica.android.automation.grounding.EmptyReason.ROOT_NODE_NULL, "空")
        val budgetLedger = ledger()
        val obs = builder(vlm = { _, _ -> "画面描述" })

        repeat(5) { obs.observe(budgetLedger, "目标") }
        assertEquals(5, budgetLedger.vlmCalls)

        val sixth = obs.observe(budgetLedger, "目标")
        assertTrue("第 6 次必须被硬顶拦下", sixth is ObservationResult.Unavailable)
        assertTrue(
            (sixth as ObservationResult.Unavailable).reason.contains("VLM")
        )
    }

    @Test
    fun testUnavailableWhenAllLevelsFail() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            TreeDumpResult.Empty(com.lirica.android.automation.grounding.EmptyReason.SCREEN_OFF_OR_LOCKED, "息屏")
        val result = builder().observe(ledger(), "做点什么")
        assertTrue(result is ObservationResult.Unavailable)
        assertEquals(
            listOf(ObservationLevel.SEMANTIC_TREE),
            (result as ObservationResult.Unavailable).attemptedLevels
        )
    }

    @Test
    fun testRoamingSensitiveNodesAreFlagged() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            okDump(listOf(node(0, "关注"), node(1, "搜索")))

        val result = builder().observe(ledger(), "浏览") as ObservationResult.Success
        assertTrue("互动词节点必须被标记，决策层据此避开漫游禁区", result.snapshot.nodes[0].isRoamingSensitive)
        assertFalse(result.snapshot.nodes[1].isRoamingSensitive)
        assertTrue(result.snapshot.promptText.contains("roam-sensitive"))
    }

    @Test
    fun testNodeLookupByRefId() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            okDump(listOf(node(0, "搜索")))
        val result = builder().observe(ledger(), "搜索") as ObservationResult.Success
        val refId = result.snapshot.nodes[0].refId
        assertTrue(result.snapshot.nodeByRef(refId) != null)
        assertTrue(result.snapshot.nodeByRef("不存在") == null)
    }

    @Test
    fun testUnknownScreenSizeFailsInsteadOfGuessingAnchors() = runTest {
        // 屏幕尺寸是归一化的唯一分母。用 coerceAtLeast(1) 兜底会把所有落点算成
        // (1000,1000)，产生"看起来成功、实际点错位置"的静默故障——必须明确失败。
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            okDump(listOf(node(0, "搜索")))

        val result = builder(screenWidth = 0, screenHeight = 0).observe(ledger(), "搜索")
        assertTrue(result is ObservationResult.Unavailable)
        assertTrue((result as ObservationResult.Unavailable).reason.contains("屏幕尺寸不可用"))
    }

    @Test
    fun testVisionGateSkipsOcrAndVlmWhenThermalOrBatteryFused() = runTest {
        coEvery { extractor.dumpTree(any(), any(), any(), any(), any()) } returns
            TreeDumpResult.Empty(com.lirica.android.automation.grounding.EmptyReason.ROOT_NODE_NULL, "空")

        var ocrCalled = false
        var vlmCalled = false
        val result = builder(
            ocr = { ocrCalled = true; listOf(ocrDetection("播放", 500, 500)) },
            vlm = { _, _ -> vlmCalled = true; "画面" },
            visionAllowed = false
        ).observe(ledger(), "看视频")

        assertTrue(result is ObservationResult.Unavailable)
        assertFalse("熔断期间不得触发本地 OCR（要截屏）", ocrCalled)
        assertFalse("熔断期间不得触发云端 VLM", vlmCalled)
        assertEquals(
            "只尝试过 L0",
            listOf(ObservationLevel.SEMANTIC_TREE),
            (result as ObservationResult.Unavailable).attemptedLevels
        )
    }

    @Test
    fun testNormalizeAndDenormalizeRoundTrip() {
        assertEquals(500, ObservationBuilder.normalize(540, 1080))
        assertEquals(20, ObservationBuilder.normalize(40, 2000))
        assertEquals(0, ObservationBuilder.normalize(100, 0))
        assertEquals(540, ObservationBuilder.denormalize(500, 1080))
        assertEquals(0, ObservationBuilder.denormalize(500, 0))
        // 往返误差不超过 1%
        val normalized = ObservationBuilder.normalize(1234, 2400)
        val back = ObservationBuilder.denormalize(normalized, 2400)
        assertTrue(kotlin.math.abs(back - 1234) <= 24)
    }
}
