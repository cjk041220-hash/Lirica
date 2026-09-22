package com.xiaoxi.android.automation.coordinator

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaoxi.android.automation.backend.ExecutionBackend
import com.xiaoxi.android.automation.capture.ScreenCaptureManager
import com.xiaoxi.android.automation.gatekeeper.FinancialGatekeeper
import com.xiaoxi.android.automation.gatekeeper.GatekeeperDecision
import com.xiaoxi.android.automation.gatekeeper.ProposedAction
import com.xiaoxi.android.automation.gesture.GestureExecutor
import com.xiaoxi.android.automation.gesture.GestureSpec
import com.xiaoxi.android.automation.gesture.TextInputExecutor
import com.xiaoxi.android.automation.grounding.ElementBounds
import com.xiaoxi.android.automation.grounding.EmptyReason
import com.xiaoxi.android.automation.grounding.ExecutionNode
import com.xiaoxi.android.automation.grounding.ExecutionTreeExtractor
import com.xiaoxi.android.automation.grounding.GroundingResult
import com.xiaoxi.android.automation.grounding.HybridGroundingEngine
import com.xiaoxi.android.automation.grounding.TreeDumpResult
import com.xiaoxi.android.automation.grounding.toUiElementNodes
import com.xiaoxi.android.automation.healing.HealingAction
import com.xiaoxi.android.automation.healing.SelfHealingWatchdog
import com.xiaoxi.android.automation.healing.SelfHealingWatchdogImpl
import com.xiaoxi.android.automation.healing.WatchdogVerdict
import com.xiaoxi.android.automation.intent.DispatchResult
import com.xiaoxi.android.automation.intent.IntentActionDispatcher
import com.xiaoxi.android.perception.AccessibilityServiceHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

interface HelixGuiCoordinator {
    val state: StateFlow<TaskExecutionState>

    suspend fun executeTask(
        goal: String,
        actions: List<CoordinatorAction>,
        maxSteps: Int = MAX_STEPS
    ): TaskExecutionResult

    fun confirmPendingAction(token: String): Boolean
    fun cancelCurrentTask()

    companion object {
        const val MAX_STEPS = 12
    }
}

class HelixGuiCoordinatorImpl(
    private val intentDispatcher: IntentActionDispatcher,
    private val treeExtractor: ExecutionTreeExtractor,
    private val groundingEngine: HybridGroundingEngine,
    private val gatekeeper: FinancialGatekeeper,
    private val gestureExecutor: GestureExecutor,
    private val textInputExecutor: TextInputExecutor,
    private val watchdog: SelfHealingWatchdog,
    private val serviceHolder: AccessibilityServiceHolder = AccessibilityServiceHolder,
    private val rootNodeProvider: () -> AccessibilityNodeInfo? = { serviceHolder.get()?.rootInActiveWindow },
    private val currentPackageProvider: () -> String? = { serviceHolder.get()?.rootInActiveWindow?.packageName?.toString() },
    // 屏幕尺寸默认值必须是 0（= 未知）而非一个看似合理的 1080x2400：
    // 1080 兜底在 1440p 等其他分辨率设备上会算出错位手势且无任何报错，
    // 属于最难排查的静默故障。生产装配必须显式注入真实 provider
    // （XiaoxiApplication 用 DisplayManager 物理显示读取）；provider 返回 0 时
    // 接地引擎会明确返回 NotFound，故障暴露在日志与自愈链路里。
    private val screenWidthProvider: () -> Int = { 0 },
    private val screenHeightProvider: () -> Int = { 0 },
    private val screenCaptureManager: ScreenCaptureManager? = null,
    private val executionBackend: ExecutionBackend? = null,
    private val confirmationTimeoutMs: Long = 300_000L
) : HelixGuiCoordinator {

    private val _state = MutableStateFlow<TaskExecutionState>(TaskExecutionState.Idle)
    override val state: StateFlow<TaskExecutionState> = _state.asStateFlow()

    private val isCancelled = AtomicBoolean(false)

    companion object {
        /** 文本注入前活节点树扫描上限，防止巨型 UI 树拖垮注入时延 */
        private const val MAX_LIVE_NODE_SCAN = 1000
    }

    override suspend fun executeTask(
        goal: String,
        actions: List<CoordinatorAction>,
        maxSteps: Int
    ): TaskExecutionResult {
        isCancelled.set(false)
        // 重置自愈状态机：上一次任务的历史步与恢复计数不得影响本次裁决
        watchdog.reset()
        _state.value = TaskExecutionState.Planning(goal)

        if (actions.isEmpty()) {
            _state.value = TaskExecutionState.Completed("无待执行动作", 0)
            return TaskExecutionResult.Success("无待执行动作", 0)
        }

        // L0 极速通道：单步标准 Intent 预检直达 (≤15ms)
        if (actions.size == 1 && actions[0] is CoordinatorAction.Intent) {
            val intentAction = (actions[0] as CoordinatorAction.Intent).action
            return when (val dispatchResult = intentDispatcher.dispatch(intentAction)) {
                is DispatchResult.Success -> {
                    _state.value = TaskExecutionState.Completed("系统原生意图直达成功", 1)
                    TaskExecutionResult.Success("系统原生意图直达成功", 1)
                }
                else -> {
                    _state.value = TaskExecutionState.Failed("系统意图分发失败: $dispatchResult", 1)
                    TaskExecutionResult.Failure("系统意图分发失败: $dispatchResult", 1)
                }
            }
        }

        // 多步有界执行主循环 (最多 MAX_STEPS 步)
        var stepIndex = 0
        for (action in actions) {
            stepIndex++

            if (stepIndex > maxSteps) {
                val reason = "达到最大执行步数上限 ($maxSteps)"
                _state.value = TaskExecutionState.Failed(reason, stepIndex)
                return TaskExecutionResult.Failure(reason, stepIndex)
            }

            if (isCancelled.get()) {
                _state.value = TaskExecutionState.Idle
                return TaskExecutionResult.Cancelled
            }

            _state.value = TaskExecutionState.ExecutingStep(
                stepIndex = stepIndex,
                totalStepsEstimate = actions.size,
                actionDescription = actionDescription(action),
                isRecovering = false
            )

            when (action) {
                is CoordinatorAction.Finish -> {
                    _state.value = TaskExecutionState.Completed(action.summary, stepIndex)
                    return TaskExecutionResult.Success(action.summary, stepIndex)
                }

                is CoordinatorAction.Intent -> {
                    val result = intentDispatcher.dispatch(action.action)
                    if (result !is DispatchResult.Success) {
                        _state.value = TaskExecutionState.Failed("意图分发失败: $result", stepIndex)
                        return TaskExecutionResult.Failure("意图分发失败: $result", stepIndex)
                    }
                }

                is CoordinatorAction.Backtrack -> {
                    serviceHolder.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                }

                is CoordinatorAction.PressKey -> {
                    val ok = if (executionBackend != null && executionBackend.isAvailable()) {
                        executionBackend.sendKeycode(action.keyCode)
                    } else {
                        when (action.keyCode) {
                            android.view.KeyEvent.KEYCODE_BACK -> serviceHolder.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) == true
                            android.view.KeyEvent.KEYCODE_HOME -> serviceHolder.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) == true
                            android.view.KeyEvent.KEYCODE_APP_SWITCH -> serviceHolder.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) == true
                            android.view.KeyEvent.KEYCODE_POWER -> serviceHolder.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) == true
                            else -> false
                        }
                    }
                    if (!ok) {
                        val failReason = "按键事件模拟失败: ${action.keyName} (keyCode=${action.keyCode})"
                        _state.value = TaskExecutionState.Failed(failReason, stepIndex)
                        return TaskExecutionResult.Failure(failReason, stepIndex)
                    }
                }

                is CoordinatorAction.Delay -> {
                    kotlinx.coroutines.delay(action.delayMs.coerceAtLeast(0L))
                }

                is CoordinatorAction.Swipe -> {
                    val w = screenWidthProvider().toFloat()
                    val h = screenHeightProvider().toFloat()
                    val (startX, startY, endX, endY) = when (action.direction) {
                        SwipeDirection.UP -> listOf(w / 2f, h * 0.8f, w / 2f, h * 0.2f)
                        SwipeDirection.DOWN -> listOf(w / 2f, h * 0.2f, w / 2f, h * 0.8f)
                        SwipeDirection.LEFT -> listOf(w * 0.8f, h / 2f, w * 0.2f, h / 2f)
                        SwipeDirection.RIGHT -> listOf(w * 0.2f, h / 2f, w * 0.8f, h / 2f)
                    }
                    if (executionBackend != null && executionBackend.isAvailable()) {
                        executionBackend.swipe(startX, startY, endX, endY, 300L)
                    } else {
                        gestureExecutor.execute(
                            GestureSpec.Swipe(startX, startY, endX, endY, humanoidCurve = action.humanoid)
                        )
                    }
                }

                is CoordinatorAction.Click, is CoordinatorAction.Type -> {
                    val query = if (action is CoordinatorAction.Click) action.query else (action as CoordinatorAction.Type).query
                    val executionSuccess = executeUiElementAction(action, query, stepIndex, actions.size)
                    if (!executionSuccess) {
                        val failedState = _state.value as? TaskExecutionState.Failed
                        val reason = failedState?.errorReason ?: "执行 UI 动作失败"
                        return TaskExecutionResult.Failure(reason, stepIndex)
                    }
                }
            }
        }

        val completedMsg = "任务代行执行完毕"
        _state.value = TaskExecutionState.Completed(completedMsg, stepIndex)
        return TaskExecutionResult.Success(completedMsg, stepIndex)
    }

    private suspend fun executeUiElementAction(
        action: CoordinatorAction,
        query: com.xiaoxi.android.automation.grounding.GroundingQuery,
        stepIndex: Int,
        totalSteps: Int
    ): Boolean {
        // 1. 抓取执行态高保真树
        var dump = treeExtractor.dumpTree(
            rootNode = rootNodeProvider(),
            screenWidth = screenWidthProvider(),
            screenHeight = screenHeightProvider(),
            packageName = currentPackageProvider()
        )

        if (dump is TreeDumpResult.Empty) {
            if (dump.reason == EmptyReason.PRIVACY_BLACKOUT) {
                _state.value = TaskExecutionState.Failed("目标应用命中银行/支付安全熔断黑名单: ${dump.message}", stepIndex)
                return false
            }

            // 评估 watchdog：树抓取为空时以包名哈希作指纹（同包连续抓取失败同样构成"卡住"语义）
            val verdict = watchdog.inspectStep(
                fingerprint = (currentPackageProvider() ?: "").hashCode().toLong(),
                currentPackage = currentPackageProvider() ?: "",
                targetPackage = null,
                actionSummary = actionDescription(action)
            )
            if (verdict is WatchdogVerdict.Recoverable) {
                _state.value = TaskExecutionState.ExecutingStep(stepIndex, totalSteps, "自愈中: ${verdict.anomaly}", isRecovering = true)
                watchdog.recordRecoveryAttempt(verdict.anomaly)
                applyRecoveryAction(verdict.suggestedAction, emptyList())
                dump = treeExtractor.dumpTree(rootNodeProvider(), screenWidthProvider(), screenHeightProvider(), currentPackageProvider())
            }
        }

        if (dump !is TreeDumpResult.Ok) {
            _state.value = TaskExecutionState.Failed("界面树抓取失败", stepIndex)
            return false
        }

        // 2. 元素接地 (HybridGrounding)
        var locateResult = groundingEngine.locate(
            query = query,
            tree = dump.nodes.toUiElementNodes(),
            screenshot = null,
            screenWidth = screenWidthProvider(),
            screenHeight = screenHeightProvider()
        )

        // 若无障碍树未找到且具备截屏能力，触发 L1 视觉/OCR 接地
        if (locateResult is GroundingResult.NotFound && screenCaptureManager != null) {
            val screenshot = screenCaptureManager.captureScreen()?.bytes
            if (screenshot != null) {
                locateResult = groundingEngine.locate(
                    query = query,
                    tree = dump.nodes.toUiElementNodes(),
                    screenshot = screenshot,
                    screenWidth = screenWidthProvider(),
                    screenHeight = screenHeightProvider()
                )
            }
        }

        // 若仍未找到，检查是否有阻断弹窗并尝试自愈
        if (locateResult is GroundingResult.NotFound) {
            val verdict = watchdog.inspectStep(
                fingerprint = pageFingerprint(dump.nodes),
                currentPackage = currentPackageProvider() ?: "",
                targetPackage = null,
                actionSummary = actionDescription(action),
                pageTexts = dump.nodes.mapNotNull { it.text }
            )
            if (verdict is WatchdogVerdict.Recoverable) {
                _state.value = TaskExecutionState.ExecutingStep(stepIndex, totalSteps, "自愈中: ${verdict.anomaly}", isRecovering = true)
                watchdog.recordRecoveryAttempt(verdict.anomaly)
                applyRecoveryAction(verdict.suggestedAction, dump.nodes)

                // 自愈后重新抓取与接地
                val retryDump = treeExtractor.dumpTree(rootNodeProvider(), screenWidthProvider(), screenHeightProvider(), currentPackageProvider())
                if (retryDump is TreeDumpResult.Ok) {
                    locateResult = groundingEngine.locate(
                        query = query,
                        tree = retryDump.nodes.toUiElementNodes(),
                        screenshot = null,
                        screenWidth = screenWidthProvider(),
                        screenHeight = screenHeightProvider()
                    )
                    // 自愈重试下同样支持 OCR 视觉接地
                    if (locateResult is GroundingResult.NotFound && screenCaptureManager != null) {
                        val retryScreenshot = screenCaptureManager.captureScreen()?.bytes
                        if (retryScreenshot != null) {
                            locateResult = groundingEngine.locate(
                                query = query,
                                tree = retryDump.nodes.toUiElementNodes(),
                                screenshot = retryScreenshot,
                                screenWidth = screenWidthProvider(),
                                screenHeight = screenHeightProvider()
                            )
                        }
                    }
                }
            }
        }

        return when (locateResult) {
            is GroundingResult.NotFound -> {
                _state.value = TaskExecutionState.Failed("未找到目标元素: $query", stepIndex)
                false
            }
            is GroundingResult.Ambiguous -> {
                _state.value = TaskExecutionState.Failed("目标元素存在歧义 (${locateResult.candidates.size} 个候选)", stepIndex)
                false
            }
            is GroundingResult.Found -> {
                val targetPoint = locateResult.targetPoint
                val refNode = locateResult.refNode
                val text = refNode?.text
                val resourceId = refNode?.resourceId
                val bounds = refNode?.bounds ?: ElementBounds(
                    left = targetPoint.x.toInt() - 20,
                    top = targetPoint.y.toInt() - 20,
                    right = targetPoint.x.toInt() + 20,
                    bottom = targetPoint.y.toInt() + 20
                )

                // 3. 零信任高危安全护栏审查 (FinancialGatekeeper)
                val isPassword = text?.contains("密码") == true
                    || query.resourceId?.contains("password", ignoreCase = true) == true
                    || (action is CoordinatorAction.Click && query.text?.contains("密码") == true)

                val proposed = ProposedAction(
                    actionType = if (action is CoordinatorAction.Click) "CLICK" else "INPUT",
                    targetText = text,
                    targetResourceId = resourceId,
                    isPasswordTarget = isPassword,
                    currentPackage = currentPackageProvider() ?: ""
                )

                val decision = gatekeeper.evaluate(proposed)
                var effectiveText = if (action is CoordinatorAction.Type) action.text else ""

                when (decision) {
                    is GatekeeperDecision.PhysicalBlocked -> {
                        _state.value = TaskExecutionState.Failed("零信任高危护栏物理熔断: ${decision.reason} (${decision.rule})", stepIndex)
                        return false
                    }
                    is GatekeeperDecision.RequireUserConfirmation -> {
                        val confirmation = CompletableDeferred<Boolean>()
                        val initialDraft = if (action is CoordinatorAction.Type) action.text else null
                        val draftTitle = if (action is CoordinatorAction.Type) "输入文本草稿确认" else "敏感操作二次确认"

                        _state.value = TaskExecutionState.AwaitingUserConfirmation(
                            token = decision.token,
                            riskDetails = decision.details.description,
                            draftContent = initialDraft,
                            draftTitle = draftTitle,
                            onConfirmed = {
                                gatekeeper.confirmAction(decision.token)
                                confirmation.complete(true)
                            },
                            onCancelled = {
                                confirmation.complete(false)
                            },
                            onConfirmedWithDraft = { edited ->
                                effectiveText = edited
                                gatekeeper.confirmAction(decision.token)
                                confirmation.complete(true)
                            }
                        )

                        try {
                            // CompletableDeferred 挂起等待二次确认（替代 while+delay 忙轮询；
                            // Deferred 的完成 happens-before 保证回调线程对 effectiveText 的写入对恢复点可见）
                            val approved = withTimeout(confirmationTimeoutMs) { confirmation.await() }
                            if (!approved || !gatekeeper.consumeApproval(decision.token)) {
                                _state.value = TaskExecutionState.Failed("用户取消或高危操作未获确认", stepIndex)
                                return false
                            }
                        } catch (e: TimeoutCancellationException) {
                            _state.value = TaskExecutionState.Failed("用户确认超时 ($confirmationTimeoutMs ms)", stepIndex)
                            return false
                        } catch (e: CancellationException) {
                            // 外层协程被取消（调用方作用域销毁等）：状态归位 Idle 再上抛，
                            // 避免 _state 残留 AwaitingUserConfirmation 影响下一次任务
                            _state.value = TaskExecutionState.Idle
                            throw e
                        }
                    }
                    is GatekeeperDecision.Allowed -> {
                        // 放行
                    }
                }

                // 4. 拟人化动作分发
                when (action) {
                    is CoordinatorAction.Click -> {
                        if (executionBackend != null && executionBackend.isAvailable()) {
                            executionBackend.click(targetPoint.x, targetPoint.y)
                        } else {
                            gestureExecutor.execute(
                                GestureSpec.Click(targetPoint.x, targetPoint.y)
                            )
                        }
                        true
                    }
                    is CoordinatorAction.Type -> {
                        val textToInject = if (effectiveText.isNotBlank()) effectiveText else action.text
                        // 接地结果 refNode 是纯数据快照 (UiElementNode)，无法直接执行 ACTION_SET_TEXT / ACTION_PASTE；
                        // 必须按 bounds 从当前活动窗口恢复活节点后再注入——node=null 时两条注入路径都会静默失效。
                        val liveNode = findLiveNodeByBounds(bounds)
                        textInputExecutor.injectText(
                            node = liveNode,
                            targetBounds = bounds,
                            text = textToInject
                        )
                    }
                    else -> true
                }
            }
        }
    }

    /**
     * 页面指纹：由节点文本与 bounds 组合的多项式哈希。
     * 同一页面反复出现时指纹一致，供 watchdog 检测卡死循环；
     * 空树返回 0L（保持"无指纹"语义）。
     */
    private fun pageFingerprint(nodes: List<ExecutionNode>): Long {
        if (nodes.isEmpty()) return 0L
        var hash = 1125899906842597L
        for (node in nodes) {
            hash = 31L * hash + (node.text?.hashCode() ?: 0)
            hash = 31L * hash + node.bounds.hashCode()
        }
        return hash
    }

    /**
     * 从当前活动窗口树中恢复与接地快照 bounds 对应的活节点：
     * 优先精确匹配 bounds；其次取与目标区域相交的可编辑节点（输入框兜底）。
     * 树遍历带上限，防止巨型 UI 树拖垮注入时延。
     */
    private fun findLiveNodeByBounds(targetBounds: ElementBounds): AccessibilityNodeInfo? {
        val root = rootNodeProvider() ?: return null
        var editableFallback: AccessibilityNodeInfo? = null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_LIVE_NODE_SCAN) {
            val node = queue.removeFirst() ?: continue
            visited++
            val rect = android.graphics.Rect()
            node.getBoundsInScreen(rect)
            val exactMatch = rect.left == targetBounds.left && rect.top == targetBounds.top &&
                rect.right == targetBounds.right && rect.bottom == targetBounds.bottom
            if (exactMatch) {
                return node
            }
            if (editableFallback == null && node.isEditable &&
                rect.intersects(targetBounds.left, targetBounds.top, targetBounds.right, targetBounds.bottom)
            ) {
                editableFallback = node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return editableFallback
    }

    private suspend fun applyRecoveryAction(action: HealingAction, currentNodes: List<ExecutionNode>) {
        when (action) {
            is HealingAction.DismissPopup -> {
                // 在当前树中寻找符合弹窗切除词表的按钮并点击
                val popupKeywords = SelfHealingWatchdogImpl.POPUP_KEYWORDS
                val candidate = currentNodes.firstOrNull { node: ExecutionNode ->
                    popupKeywords.any { kw: String ->
                        node.text?.contains(kw) == true || node.contentDescription?.contains(kw) == true
                    }
                }
                if (candidate != null) {
                    gestureExecutor.execute(
                        GestureSpec.Click(candidate.bounds.centerX.toFloat(), candidate.bounds.centerY.toFloat())
                    )
                }
            }
            is HealingAction.PhysicalBacktrack -> {
                serviceHolder.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            is HealingAction.ReGround -> {
                // 重新接地只需刷新重试
            }
            is HealingAction.EscalateToUser -> {
                // 无法自愈，将在上层终止
            }
        }
    }

    private fun actionDescription(action: CoordinatorAction): String = when (action) {
        is CoordinatorAction.Intent -> "分发系统意图"
        is CoordinatorAction.Click -> action.description
        is CoordinatorAction.Type -> action.description
        is CoordinatorAction.Swipe -> action.description
        is CoordinatorAction.Backtrack -> action.description
        is CoordinatorAction.PressKey -> action.description
        is CoordinatorAction.Delay -> action.description
        is CoordinatorAction.Finish -> action.summary
    }

    override fun confirmPendingAction(token: String): Boolean {
        val currentState = _state.value
        if (currentState is TaskExecutionState.AwaitingUserConfirmation && currentState.token == token) {
            currentState.onConfirmed()
            return true
        }
        return false
    }

    override fun cancelCurrentTask() {
        isCancelled.set(true)
        val currentState = _state.value
        if (currentState is TaskExecutionState.AwaitingUserConfirmation) {
            currentState.onCancelled()
        }
        _state.value = TaskExecutionState.Idle
    }
}
