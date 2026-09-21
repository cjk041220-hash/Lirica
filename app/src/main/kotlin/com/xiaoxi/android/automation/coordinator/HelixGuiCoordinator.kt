package com.xiaoxi.android.automation.coordinator

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val screenWidthProvider: () -> Int = { 1080 },
    private val screenHeightProvider: () -> Int = { 2400 },
    private val confirmationTimeoutMs: Long = 300_000L
) : HelixGuiCoordinator {

    private val _state = MutableStateFlow<TaskExecutionState>(TaskExecutionState.Idle)
    override val state: StateFlow<TaskExecutionState> = _state.asStateFlow()

    private val isCancelled = AtomicBoolean(false)

    override suspend fun executeTask(
        goal: String,
        actions: List<CoordinatorAction>,
        maxSteps: Int
    ): TaskExecutionResult {
        isCancelled.set(false)
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

                is CoordinatorAction.Swipe -> {
                    val w = screenWidthProvider().toFloat()
                    val h = screenHeightProvider().toFloat()
                    val (startX, startY, endX, endY) = when (action.direction) {
                        SwipeDirection.UP -> listOf(w / 2f, h * 0.8f, w / 2f, h * 0.2f)
                        SwipeDirection.DOWN -> listOf(w / 2f, h * 0.2f, w / 2f, h * 0.8f)
                        SwipeDirection.LEFT -> listOf(w * 0.8f, h / 2f, w * 0.2f, h / 2f)
                        SwipeDirection.RIGHT -> listOf(w * 0.2f, h / 2f, w * 0.8f, h / 2f)
                    }
                    gestureExecutor.execute(
                        GestureSpec.Swipe(startX, startY, endX, endY, humanoidCurve = action.humanoid)
                    )
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

            // 评估 watchdog
            val verdict = watchdog.inspectStep(0L, currentPackageProvider() ?: "", null, actionDescription(action))
            if (verdict is WatchdogVerdict.Recoverable) {
                _state.value = TaskExecutionState.ExecutingStep(stepIndex, totalSteps, "自愈中: ${verdict.anomaly}", isRecovering = true)
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

        // 若未找到，检查是否有阻断弹窗并尝试自愈
        if (locateResult is GroundingResult.NotFound) {
            val verdict = watchdog.inspectStep(
                fingerprint = 0L,
                currentPackage = currentPackageProvider() ?: "",
                targetPackage = null,
                actionSummary = actionDescription(action),
                pageTexts = dump.nodes.mapNotNull { it.text }
            )
            if (verdict is WatchdogVerdict.Recoverable) {
                _state.value = TaskExecutionState.ExecutingStep(stepIndex, totalSteps, "自愈中: ${verdict.anomaly}", isRecovering = true)
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
                        var confirmed = false
                        var cancelled = false
                        val initialDraft = if (action is CoordinatorAction.Type) action.text else null
                        val draftTitle = if (action is CoordinatorAction.Type) "输入文本草稿确认" else "敏感操作二次确认"

                        _state.value = TaskExecutionState.AwaitingUserConfirmation(
                            token = decision.token,
                            riskDetails = decision.details.description,
                            draftContent = initialDraft,
                            draftTitle = draftTitle,
                            onConfirmed = {
                                gatekeeper.confirmAction(decision.token)
                                confirmed = true
                            },
                            onCancelled = {
                                cancelled = true
                            },
                            onConfirmedWithDraft = { edited ->
                                effectiveText = edited
                                gatekeeper.confirmAction(decision.token)
                                confirmed = true
                            }
                        )

                        val startTime = System.currentTimeMillis()
                        // 挂起等待二次确认 (带超时熔断保护)
                        while (!confirmed && !cancelled && !isCancelled.get()) {
                            if (System.currentTimeMillis() - startTime >= confirmationTimeoutMs) {
                                cancelled = true
                                _state.value = TaskExecutionState.Failed("用户确认超时 ($confirmationTimeoutMs ms)", stepIndex)
                                return false
                            }
                            delay(50L)
                        }

                        if (!confirmed || !gatekeeper.consumeApproval(decision.token)) {
                            _state.value = TaskExecutionState.Failed("用户取消或高危操作未获确认", stepIndex)
                            return false
                        }
                    }
                    is GatekeeperDecision.Allowed -> {
                        // 放行
                    }
                }

                // 4. 拟人化动作分发
                when (action) {
                    is CoordinatorAction.Click -> {
                        gestureExecutor.execute(
                            GestureSpec.Click(targetPoint.x, targetPoint.y)
                        )
                        true
                    }
                    is CoordinatorAction.Type -> {
                        val textToInject = if (effectiveText.isNotBlank()) effectiveText else action.text
                        textInputExecutor.injectText(
                            node = null,
                            targetBounds = bounds,
                            text = textToInject
                        )
                    }
                    else -> true
                }
            }
        }
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
