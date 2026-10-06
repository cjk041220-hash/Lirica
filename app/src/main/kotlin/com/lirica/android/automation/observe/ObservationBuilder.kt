package com.lirica.android.automation.observe

import android.view.accessibility.AccessibilityNodeInfo
import com.lirica.android.automation.grounding.ElementBounds
import com.lirica.android.automation.grounding.ExecutionNode
import com.lirica.android.automation.grounding.ExecutionTreeExtractor
import com.lirica.android.automation.grounding.TreeDumpResult
import com.lirica.android.automation.policy.ReadOnlyGuard
import com.lirica.android.automation.verify.ActionExecutionBudget
import com.lirica.android.automation.verify.BudgetDimension
import com.lirica.android.automation.verify.BudgetLedger

/**
 * 多级观测表征构建器（主方案5.0 §5.1.1 · P2-1 全量吸收）。
 *
 * 硬约束（全部来自方案，改动即违约）：
 * 1. Level 0 候选交互节点 **≤ 30 项**，Prompt 字符数 **≤ 8KB**；
 * 2. Level 1 仅在 Level 0 拿不到可用交互面时触发（自绘 Canvas / 纯 WebView 无节点）；
 * 3. Level 2 云端 VLM 仅在前两级均失效时兜底，且**单任务硬顶 5 次**（走 [BudgetLedger]）；
 * 4. 坐标一律归一化到 [0,1000]，绝不把物理像素喂给决策层。
 */
class ObservationBuilder(
    private val treeExtractor: ExecutionTreeExtractor,
    private val rootNodeProvider: () -> AccessibilityNodeInfo?,
    private val screenWidthProvider: () -> Int,
    private val screenHeightProvider: () -> Int,
    private val packageProvider: () -> String?,
    /** Level 1 端侧 OCR（ML Kit）文本提供者；null 表示设备/环境不支持 */
    private val localOcrProvider: (suspend () -> List<String>)? = null,
    /** Level 2 云端 VLM 摘要提供者；null 表示未配置多模态通道 */
    private val cloudVlmProvider: (suspend (goal: String, nodeSummary: String) -> String?)? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val readOnlyGuard: ReadOnlyGuard = ReadOnlyGuard(),
    /**
     * 视觉重通道闸门（§7.3 热功耗与电量梯度限流）。
     *
     * 为 false 时跳过 L1 本地 OCR 与 L2 云端 VLM：这两级都要截屏，
     * 而截屏正是 42℃ 熔断线与"电量 <20% 禁用多模态"要拦的东西。
     * L0 语义树不受影响——熔断只砍重通道，不砍轻量动作。
     */
    private val visionAllowedProvider: () -> Boolean = { true }
) {

    /**
     * 执行一次观测。
     *
     * @param ledger 预算账本（VLM 调用在此扣减，超顶即拒绝降级）
     * @param goal 任务目标（Level 2 VLM 提示词需要）
     */
    suspend fun observe(
        ledger: BudgetLedger,
        goal: String,
        budget: ActionExecutionBudget = ledger.budget
    ): ObservationResult {
        val attempted = mutableListOf<ObservationLevel>()

        // 屏幕尺寸是归一化坐标的唯一分母。尺寸未知时必须**明确失败**：
        // 用 coerceAtLeast(1) 兜底会把所有落点算成 (1000,1000)，
        // 产生"看起来成功、实际点错位置"的静默故障——比失败贵得多。
        val screenWidth = screenWidthProvider()
        val screenHeight = screenHeightProvider()
        if (screenWidth <= 0 || screenHeight <= 0) {
            return ObservationResult.Unavailable(
                attemptedLevels = attempted,
                reason = "屏幕尺寸不可用（w=" + screenWidth + ", h=" + screenHeight + "）：拒绝用猜测比例生成落点"
            )
        }

        // ---------------- Level 0：语义裁剪树 ----------------
        attempted += ObservationLevel.SEMANTIC_TREE
        val dump = treeExtractor.dumpTree(
            rootNode = rootNodeProvider(),
            screenWidth = screenWidthProvider(),
            screenHeight = screenHeightProvider(),
            packageName = packageProvider()
        )
        if (dump is TreeDumpResult.Ok && dump.nodes.isNotEmpty()) {
            val snapshot = buildSemanticSnapshot(dump, budget)
            if (snapshot.nodes.isNotEmpty()) return ObservationResult.Success(snapshot)
        }

        val semanticReason = when (dump) {
            is TreeDumpResult.Ok -> "语义树中无可用交互节点（节点数 " + dump.nodes.size + "）"
            is TreeDumpResult.Empty -> dump.message
            is TreeDumpResult.Failed -> dump.message
        }

        // ---------------- Level 1：端侧 OCR ----------------
        val ocrProvider = localOcrProvider
        if (ocrProvider != null && visionAllowedProvider()) {
            attempted += ObservationLevel.LOCAL_OCR
            val ocrLines = runCatching { ocrProvider.invoke() }.getOrDefault(emptyList())
            if (ocrLines.isNotEmpty()) {
                val snapshot = buildOcrSnapshot(ocrLines, budget)
                if (snapshot.nodes.isNotEmpty()) return ObservationResult.Success(snapshot)
            }
        }

        // ---------------- Level 2：云端 VLM（预算硬顶） ----------------
        val vlmProvider = cloudVlmProvider
        if (vlmProvider != null && visionAllowedProvider()) {
            attempted += ObservationLevel.CLOUD_VLM
            val vlmBudget = ledger.consumeVlmCall()
            if (vlmBudget is com.lirica.android.automation.verify.BudgetCheck.Exhausted) {
                return ObservationResult.Unavailable(
                    attemptedLevels = attempted,
                    reason = semanticReason + "；且 " + vlmBudget.reason
                )
            }
            val summary = runCatching { vlmProvider.invoke(goal, semanticReason) }.getOrNull()
            if (!summary.isNullOrBlank()) {
                val snapshot = buildVlmSnapshot(summary, budget)
                return ObservationResult.Success(snapshot)
            }
        }

        return ObservationResult.Unavailable(
            attemptedLevels = attempted,
            reason = semanticReason
        )
    }

    // ------------------------------------------------------------------
    // Level 0
    // ------------------------------------------------------------------

    private fun buildSemanticSnapshot(dump: TreeDumpResult.Ok, budget: ActionExecutionBudget): ObservationSnapshot {
        val nodeSummary = summarizeNodes(dump.nodes, budget)
        val header = buildString {
            append("pkg=").append(dump.packageName)
            dump.activityName?.let { append(" act=").append(it) }
            append(" nodes=").append(nodeSummary.nodes.size)
            if (nodeSummary.truncatedCount > 0) {
                append(" truncated=").append(nodeSummary.truncatedCount)
            }
        }
        val prompt = renderPrompt(header, nodeSummary.nodes, budget)
        return ObservationSnapshot(
            level = ObservationLevel.SEMANTIC_TREE,
            packageName = dump.packageName,
            activityName = dump.activityName,
            nodes = nodeSummary.nodes,
            truncatedCount = nodeSummary.truncatedCount,
            promptText = prompt,
            capturedAtMs = timeProvider()
        )
    }

    private data class NodeSummary(val nodes: List<ObservationNode>, val truncatedCount: Int)

    private fun summarizeNodes(nodes: List<ExecutionNode>, budget: ActionExecutionBudget): NodeSummary {
        val screenWidth = screenWidthProvider().coerceAtLeast(1)
        val screenHeight = screenHeightProvider().coerceAtLeast(1)

        // 候选筛选：可见 + 可交互或含语义文本；大容器仅保留滚动能力标识
        val candidates = nodes.filter { node ->
            node.isVisibleToUser && (
                node.isClickable || node.isEditable || node.isScrollable ||
                    !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()
                )
        }

        // 优先级排序（沿用既有 ExecutionNode.priority），同级按文档顺序保持稳定
        val ordered = candidates.sortedWith(
            compareByDescending<ExecutionNode> { it.priority }
                .thenBy { it.bounds.top }
                .thenBy { it.bounds.left }
        )

        val kept = ArrayList<ObservationNode>(budget.maxObservationNodes)
        var truncated = 0
        val scrollableTracked = ArrayList<ElementBounds>()

        for (node in ordered) {
            if (kept.size >= budget.maxObservationNodes) {
                truncated++
                continue
            }
            val normalized = node.toObservationNode(
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                refId = "n" + kept.size,
                inScrollableContainer = scrollableTracked.any { it.contains(node.bounds.centerX, node.bounds.centerY) }
            )
            kept.add(normalized)
            if (node.isScrollable) scrollableTracked.add(node.bounds)
        }

        return NodeSummary(nodes = kept, truncatedCount = truncated)
    }

    private fun ExecutionNode.toObservationNode(
        screenWidth: Int,
        screenHeight: Int,
        refId: String,
        inScrollableContainer: Boolean
    ): ObservationNode {
        val label = text ?: contentDescription
        val roamingSensitive = label != null && readOnlyGuard.evaluate(
            actionId = com.lirica.android.automation.policy.SystemActionCatalog.ID_UI_CLICK,
            targetText = label
        ) is ReadOnlyGuard.Verdict.RequiresNegotiation

        return ObservationNode(
            refId = refId,
            text = text,
            contentDescription = contentDescription,
            resourceId = resourceId,
            className = className,
            role = resolveRole(this),
            normalizedX = normalize(bounds.centerX, screenWidth),
            normalizedY = normalize(bounds.centerY, screenHeight),
            isClickable = isClickable,
            isEditable = isEditable,
            isEnabled = isEnabled,
            isChecked = isChecked,
            isScrollable = isScrollable,
            isRoamingSensitive = roamingSensitive,
            inScrollableContainer = inScrollableContainer
        )
    }

    private fun resolveRole(node: ExecutionNode): NodeRole = when {
        node.isEditable -> NodeRole.INPUT
        node.isCheckable -> NodeRole.TOGGLE
        node.isScrollable && node.isLargeContainer -> NodeRole.SCROLL
        node.isClickable -> NodeRole.BUTTON
        node.className?.contains("Image", ignoreCase = true) == true -> NodeRole.IMAGE
        !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() -> NodeRole.TEXT
        else -> NodeRole.OTHER
    }

    // ------------------------------------------------------------------
    // Level 1 / Level 2
    // ------------------------------------------------------------------

    private fun buildOcrSnapshot(lines: List<String>, budget: ActionExecutionBudget): ObservationSnapshot {
        val nodes = lines.take(budget.maxObservationNodes).mapIndexed { index, line ->
            ObservationNode(
                refId = "o" + index,
                text = line,
                contentDescription = null,
                resourceId = null,
                className = null,
                role = NodeRole.TEXT,
                normalizedX = 500,
                normalizedY = 500,
                isClickable = false,
                isEditable = false,
                isEnabled = true,
                isChecked = false,
                isScrollable = false
            )
        }
        val header = "level=LOCAL_OCR lines=" + nodes.size
        return ObservationSnapshot(
            level = ObservationLevel.LOCAL_OCR,
            packageName = packageProvider() ?: "",
            activityName = null,
            nodes = nodes,
            truncatedCount = (lines.size - nodes.size).coerceAtLeast(0),
            promptText = renderPrompt(header, nodes, budget),
            capturedAtMs = timeProvider()
        )
    }

    private fun buildVlmSnapshot(summary: String, budget: ActionExecutionBudget): ObservationSnapshot {
        val clipped = summary.take(budget.maxObservationChars)
        val pseudoNode = ObservationNode(
            refId = "v0",
            text = clipped,
            contentDescription = null,
            resourceId = null,
            className = null,
            role = NodeRole.OTHER,
            normalizedX = 500,
            normalizedY = 500,
            isClickable = false,
            isEditable = false,
            isEnabled = true,
            isChecked = false,
            isScrollable = false
        )
        return ObservationSnapshot(
            level = ObservationLevel.CLOUD_VLM,
            packageName = packageProvider() ?: "",
            activityName = null,
            nodes = listOf(pseudoNode),
            truncatedCount = 0,
            promptText = "level=CLOUD_VLM\n" + clipped,
            capturedAtMs = timeProvider()
        )
    }

    // ------------------------------------------------------------------
    // Prompt 渲染（8KB 硬顶）
    // ------------------------------------------------------------------

    private fun renderPrompt(
        header: String,
        nodes: List<ObservationNode>,
        budget: ActionExecutionBudget
    ): String {
        val builder = StringBuilder()
        builder.append(header)
        for (node in nodes) {
            val line = "\n" + node.render()
            // 预算控制：宁可少给一行，也不允许突破 8KB 把上下文挤爆
            if (builder.length + line.length > budget.maxObservationChars) break
            builder.append(line)
        }
        return builder.toString()
    }

    companion object {
        /** 物理像素 → [0,1000] 归一化比例空间（§7.2） */
        fun normalize(pixel: Int, screenExtent: Int): Int {
            if (screenExtent <= 0) return 0
            return ((pixel.toLong() * NORMALIZED_MAX) / screenExtent).toInt().coerceIn(0, NORMALIZED_MAX)
        }

        /** 归一化坐标 → 物理像素（接地落地时使用） */
        fun denormalize(normalized: Int, screenExtent: Int): Int {
            if (screenExtent <= 0) return 0
            return ((normalized.toLong() * screenExtent) / NORMALIZED_MAX).toInt().coerceIn(0, screenExtent)
        }

        const val NORMALIZED_MAX = 1000

        /** 预算维度别名，供测试断言使用 */
        val VLM_DIMENSION: BudgetDimension = BudgetDimension.VLM_CALLS
    }
}

/** 矩形包含判定（避免依赖 android.graphics.Rect，保持纯 JVM 可测） */
private fun ElementBounds.contains(x: Int, y: Int): Boolean =
    x >= left && x <= right && y >= top && y <= bottom
