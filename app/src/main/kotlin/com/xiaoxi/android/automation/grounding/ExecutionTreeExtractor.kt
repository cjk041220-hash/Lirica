package com.xiaoxi.android.automation.grounding

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaoxi.android.perception.PrivacyBreaker
import kotlinx.coroutines.delay

class ExecutionTreeExtractor(
    private val privacyBreaker: PrivacyBreaker = PrivacyBreaker(),
    private val retryDelayMs: Long = 600L
) {
    companion object {
        const val MAX_EXECUTION_NODES = 400
        const val MAX_DEPTH = 12
    }

    /**
     * 抽取执行态高保真树，自带两轮自愈与竞态取优
     */
    suspend fun dumpTree(
        rootNode: AccessibilityNodeInfo?,
        screenWidth: Int,
        screenHeight: Int,
        packageName: String? = null,
        activityName: String? = null
    ): TreeDumpResult {
        val startTs = System.currentTimeMillis()

        // 1. 银行/支付物理安全熔断
        if (packageName != null && privacyBreaker.isSensitiveApp(packageName)) {
            return TreeDumpResult.Empty(
                reason = EmptyReason.PRIVACY_BLACKOUT,
                message = "目标应用 [$packageName] 命中银行/支付物理安全黑名单，整屏熔断"
            )
        }

        if (rootNode == null) {
            return TreeDumpResult.Empty(
                reason = EmptyReason.ROOT_NODE_NULL,
                message = "AccessibilityNodeInfo 根节点为 null"
            )
        }

        // 2. 第一轮抽取
        val round1Nodes = extractNodes(rootNode, screenWidth, screenHeight)
        val interactableCount1 = round1Nodes.count { it.isClickable || it.isEditable }

        // 3. 切屏竞态抗抖动自愈：若有效可交互节点 <= 2，可能遭遇了窗口重绘竞态，非阻塞等待后进行第二轮抓取
        if (interactableCount1 <= 2) {
            delay(retryDelayMs)
            val round2Nodes = extractNodes(rootNode, screenWidth, screenHeight)
            val interactableCount2 = round2Nodes.count { it.isClickable || it.isEditable }

            if (interactableCount2 > interactableCount1) {
                val duration = System.currentTimeMillis() - startTs
                return TreeDumpResult.Ok(
                    packageName = packageName ?: rootNode.packageName?.toString().orEmpty(),
                    activityName = activityName,
                    nodes = round2Nodes,
                    totalDiscovered = round2Nodes.size,
                    isTruncated = round2Nodes.size >= MAX_EXECUTION_NODES,
                    omittedCount = 0,
                    isRecoveredFromRetry = true,
                    dumpDurationMs = duration
                )
            }
        }

        // 4. 正常返回第一轮结果
        val duration = System.currentTimeMillis() - startTs
        return if (round1Nodes.isEmpty()) {
            TreeDumpResult.Empty(
                reason = EmptyReason.ZERO_WINDOW_TIMED_OUT,
                message = "连续两轮抓取有效节点均为 0，窗口超时"
            )
        } else {
            TreeDumpResult.Ok(
                packageName = packageName ?: rootNode.packageName?.toString().orEmpty(),
                activityName = activityName,
                nodes = round1Nodes,
                totalDiscovered = round1Nodes.size,
                isTruncated = round1Nodes.size >= MAX_EXECUTION_NODES,
                omittedCount = 0,
                isRecoveredFromRetry = false,
                dumpDurationMs = duration
            )
        }
    }

    internal fun extractNodes(
        root: AccessibilityNodeInfo,
        screenWidth: Int,
        screenHeight: Int
    ): List<ExecutionNode> {
        val result = mutableListOf<ExecutionNode>()
        val screenArea = screenWidth * screenHeight
        val tempRect = Rect()

        fun traverse(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > MAX_DEPTH || result.size >= MAX_EXECUTION_NODES) return

            node.getBoundsInScreen(tempRect)
            val bounds = ElementBounds(
                left = tempRect.left,
                top = tempRect.top,
                right = tempRect.right,
                bottom = tempRect.bottom
            )

            // 大容器抑制：面积 > 50% 屏幕面积且具备滚动属性
            val isLarge = bounds.area > (screenArea / 2) && node.isScrollable

            // 优先级打分 (0~5)
            val priority = when {
                node.isEditable -> 5
                node.isClickable && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) -> 5
                !node.text.isNullOrBlank() -> 4
                node.isClickable -> 3
                isLarge -> 1
                else -> 1
            }

            // 密码框安全脱敏保护
            val safeText = if (node.isPassword) "[PROTECTED_PASSWORD]" else node.text?.toString()

            val executionNode = ExecutionNode(
                nodeId = "${node.viewIdResourceName ?: "node"}_${result.size}",
                text = safeText,
                contentDescription = node.contentDescription?.toString(),
                resourceId = node.viewIdResourceName,
                className = node.className?.toString(),
                bounds = bounds,
                isClickable = node.isClickable,
                isEditable = node.isEditable,
                isCheckable = node.isCheckable,
                isChecked = node.isChecked,
                isScrollable = node.isScrollable,
                isEnabled = node.isEnabled,
                isVisibleToUser = node.isVisibleToUser,
                isPassword = node.isPassword,
                priority = priority,
                isLargeContainer = isLarge
            )

            result.add(executionNode)

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverse(child, depth + 1)
            }
        }

        traverse(root, 0)
        return result
    }
}
