package com.lirica.android.automation.coordinator

import com.lirica.android.automation.feedback.ActionVisualFeedback
import com.lirica.android.automation.feedback.ActionVisualFeedbackListener
import com.lirica.android.automation.feedback.EmbodiedState
import com.lirica.android.automation.feedback.NoOpActionVisualFeedbackListener
import com.lirica.android.automation.grounding.GroundingQuery
import com.lirica.android.automation.healing.FailureContext
import com.lirica.android.automation.healing.FailureScenario
import com.lirica.android.automation.healing.HealingAction
import com.lirica.android.automation.healing.HonestNarrator
import com.lirica.android.automation.healing.HonestNarratorImpl
import com.lirica.android.automation.healing.SelfHealingWatchdog
import com.lirica.android.automation.healing.WatchdogVerdict
import com.lirica.android.automation.macro.ActionMacro
import com.lirica.android.automation.macro.MacroRecordSpec
import com.lirica.android.automation.macro.MacroReplayEngine
import com.lirica.android.automation.macro.MacroReplayOutcome
import com.lirica.android.automation.macro.MacroStep
import com.lirica.android.automation.macro.MacroStepExecution
import com.lirica.android.automation.macro.MacroStepKind
import com.lirica.android.automation.macro.TrajectoryMemory
import com.lirica.android.automation.observe.ObservationBuilder
import com.lirica.android.automation.observe.ObservationLevel
import com.lirica.android.automation.observe.ObservationResult
import com.lirica.android.automation.observe.ObservationSnapshot
import com.lirica.android.automation.policy.ActionExecutionMode
import com.lirica.android.automation.policy.PhysicalGuardVerdict
import com.lirica.android.automation.policy.PhysicalResourceGuard
import com.lirica.android.automation.policy.SafetyPolicyEngine
import com.lirica.android.automation.trace.ExecutionTraceRecorder
import com.lirica.android.automation.trace.MacroHitRecord
import com.lirica.android.automation.trace.TraceFinalState
import com.lirica.android.automation.trace.TraceStep
import com.lirica.android.automation.verify.ActionExecutionBudget
import com.lirica.android.automation.verify.ActionVerification
import com.lirica.android.automation.verify.BudgetCheck
import com.lirica.android.automation.verify.BudgetLedger
import com.lirica.android.automation.verify.NodeStateToken
import com.lirica.android.automation.verify.PageFingerprint
import com.lirica.android.automation.verify.StateFingerprintService
import com.lirica.android.automation.verify.UserInterruptionDetector
import com.lirica.android.common.logging.LiricaLog

/**
 * 单步动态 ReAct 闭环（主方案5.0 §5.1 · P1-1 落地）。
 *
 * 循环契约：Observe -> Ground & Decide -> Act -> Verify -> Reflect & Self-Healing。
 * 四条硬边界（§1.2 有界自愈公理）在 [BudgetLedger] 里强制，不在业务分支里"记得判"：
 * 12 步 / 3 次自愈 / 5 次 VLM / 120 秒。
 *
 * 与 Coordinator 的关系（方案 §二 映射矩阵裁决）：
 * - **不新建平行执行引擎**：所有原子动作经 [AtomicActionExecutor] 走同一条落地通道；
 * - 本类只负责"循环、核销、熔断、轨迹"，决策交 [DynamicStepPlanner]，
 *   动作落地交 Coordinator，安全裁决交 SafetyPolicyEngine；
 * - 静态动作列表执行路径（executeTask）保持原样，聊天层工具面零改动。
 *
 * 关键设计取舍（写下来防止后人"优化"掉）：
 * 1. **"没看清"不等于"没生效"**：页面仍在震荡时返回 Unstable，不消耗自愈额度；
 * 2. **用户抢占优先级高于一切**：检测到人工触控立即停止派发，且默认不自动恢复（R4）；
 * 3. **宏回放失败必须能平滑降级**：回放中途任何一步核销不过，立刻中止回放转动态闭环，
 *    绝不让"一条坏宏"把整个任务判死；
 * 4. **只有显式声明意图签名的任务才录宏**：否则一次性的点击序列会把宏库塞满噪音。
 */
class AutonomousActionLoop(
    private val observer: ObservationBuilder,
    private val planner: DynamicStepPlanner,
    private val executor: AtomicActionExecutor,
    private val fingerprintService: StateFingerprintService,
    private val interruptionDetector: UserInterruptionDetector,
    private val watchdog: SelfHealingWatchdog,
    private val traceRecorder: ExecutionTraceRecorder,
    private val honestNarrator: HonestNarrator = HonestNarratorImpl(),
    private val feedback: ActionVisualFeedbackListener = NoOpActionVisualFeedbackListener,
    private val trajectoryMemory: TrajectoryMemory? = null,
    private val macroReplayEngine: MacroReplayEngine = MacroReplayEngine(),
    private val policyEngine: SafetyPolicyEngine? = null,
    private val resourceGuard: PhysicalResourceGuard? = null,
    private val budget: ActionExecutionBudget = ActionExecutionBudget.DEFAULT,
    private val currentPackageProvider: () -> String? = { null },
    private val appVersionCodeProvider: (String) -> Long = { 0L },
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val interruptionAwaitMs: Long = DEFAULT_INTERRUPTION_AWAIT_MS,
    /**
     * 外部取消钩子（用户点"停下"）。
     *
     * 没有它的话，`cancelCurrentTask()` 只能改变状态显示，闭环仍会继续把手势打完——
     * 用户按了停止、屏幕还在自己动，是比失败更严重的问题。
     */
    private val cancellationCheck: () -> Boolean = { false }
) {

    suspend fun run(request: AutonomousTaskRequest): AutonomousTaskResult {
        val mode = policyEngine?.mode?.value ?: ActionExecutionMode.SHARED
        val builder = traceRecorder.begin(request.goal, mode)
        val ledger = BudgetLedger(budget, timeProvider)
        ledger.start()
        watchdog.reset()

        var finalState = TraceFinalState.FAILED
        try {
            // ---- 0. 物理资源闸门（§7.3）----
            val guardVerdict = resourceGuard?.evaluate()
            if (guardVerdict is PhysicalGuardVerdict.BlockAll) {
                finalState = TraceFinalState.POLICY_BLOCKED
                return AutonomousTaskResult.PolicyBlocked(
                    rule = guardVerdict.rule,
                    reason = guardVerdict.reason,
                    honestReason = "手机现在有点烫，我先停一停，等凉下来再替你弄"
                )
            }

            // ---- 1. 经验轨迹回放尝试（M5.3 §5.2）----
            val replayOutcome = tryMacroReplay(request, ledger, builder)
            if (replayOutcome is MacroReplayOutcome.Success) {
                finalState = TraceFinalState.SUCCESS
                emit(feedback, ActionVisualFeedback.BubbleState(EmbodiedState.SUCCESS, "照着上次的路子走完了"))
                return AutonomousTaskResult.Success(
                    message = "按经验轨迹完成（" + replayOutcome.steps + " 步）",
                    steps = replayOutcome.steps,
                    macroId = replayOutcome.macroId,
                    replayedFromMacro = true
                )
            }

            // ---- 2. 动态闭环 ----
            val result = runDynamicLoop(request, ledger, builder, mode)
            finalState = when (result) {
                is AutonomousTaskResult.Success -> TraceFinalState.SUCCESS
                is AutonomousTaskResult.Cancelled -> TraceFinalState.CANCELLED
                is AutonomousTaskResult.PolicyBlocked -> TraceFinalState.POLICY_BLOCKED
                is AutonomousTaskResult.UserInterrupted -> TraceFinalState.USER_INTERRUPTED
                is AutonomousTaskResult.Failure -> result.finalState
            }
            return result
        } catch (e: kotlinx.coroutines.CancellationException) {
            finalState = TraceFinalState.CANCELLED
            throw e
        } finally {
            val trace = builder.finish(finalState, timeProvider())
            traceRecorder.complete(trace)
        }
    }

    // =====================================================================
    // 宏回放
    // =====================================================================

    private suspend fun tryMacroReplay(
        request: AutonomousTaskRequest,
        ledger: BudgetLedger,
        builder: com.lirica.android.automation.trace.ExecutionTraceBuilder
    ): MacroReplayOutcome? {
        val memory = trajectoryMemory ?: return null
        val signature = request.intentSignature ?: return null
        val appPackage = currentPackageProvider() ?: return null
        if (appPackage.isBlank()) return null

        val versionCode = appVersionCodeProvider(appPackage)
        val candidate = memory.findUsable(signature, appPackage, versionCode) ?: return null

        val startPage = fingerprintService.sampleOnce()
        emit(feedback, ActionVisualFeedback.BubbleState(EmbodiedState.REPLAYING, "这条我熟，我先按老路子试"))

        val outcome = macroReplayEngine.replay(
            macro = candidate,
            slots = request.slots,
            currentPage = startPage,
            maxSteps = budget.maxSteps,
            executor = macroStepExecutor(builder, ledger, candidate.macroId, candidate.steps.size)
        )

        when (outcome) {
            is MacroReplayOutcome.Success -> {
                memory.markReplaySuccess(candidate.macroId)
                ledger.resetMacroFailures()
                builder.recordMacroHit(
                    MacroHitRecord(
                        macroId = candidate.macroId,
                        intentSignature = signature,
                        startPageVerified = true,
                        replaySteps = outcome.steps,
                        downgradedToReAct = false
                    )
                )
            }
            is MacroReplayOutcome.StartPageMismatch -> {
                memory.markReplayFailure(candidate.macroId)
                builder.recordMacroHit(
                    MacroHitRecord(
                        macroId = candidate.macroId,
                        intentSignature = signature,
                        startPageVerified = false,
                        replaySteps = 0,
                        downgradedToReAct = true,
                        downgradeReason = "起始页指纹不匹配：" + outcome.reason
                    )
                )
                emit(feedback, ActionVisualFeedback.BubbleState(EmbodiedState.OBSERVING, "页面和上次不一样了，我重新看一遍"))
            }
            is MacroReplayOutcome.StepFailed, is MacroReplayOutcome.Blocked, is MacroReplayOutcome.MissingSlots -> {
                memory.markReplayFailure(candidate.macroId)
                val reason = when (outcome) {
                    is MacroReplayOutcome.StepFailed -> "第 " + (outcome.stepIndex + 1) + " 步未生效：" + outcome.reason
                    is MacroReplayOutcome.Blocked -> "第 " + (outcome.stepIndex + 1) + " 步被安全闸门拦截：" + outcome.reason
                    is MacroReplayOutcome.MissingSlots -> "缺少显式槽位：" + outcome.missingKeys.joinToString(",")
                    else -> "回放失败"
                }
                builder.recordMacroHit(
                    MacroHitRecord(
                        macroId = candidate.macroId,
                        intentSignature = signature,
                        startPageVerified = true,
                        replaySteps = 0,
                        downgradedToReAct = true,
                        downgradeReason = reason
                    )
                )
                LiricaLog.i("AutonomousActionLoop", "宏回放中止并降级为动态闭环：" + reason)
            }
            is MacroReplayOutcome.NotReplayable -> {
                LiricaLog.w("AutonomousActionLoop", "宏不可回放：" + outcome.reason)
            }
        }
        return outcome
    }

    private fun macroStepExecutor(
        builder: com.lirica.android.automation.trace.ExecutionTraceBuilder,
        ledger: BudgetLedger,
        macroId: String,
        totalSteps: Int
    ): suspend (MacroStep, String?, Int) -> MacroStepExecution = { step, resolvedText, stepIndex ->
        // 回放进度可见性：悬浮层据此显示"第 n 步（走老路子）"，
        // 避免长回放期间界面毫无反馈、用户以为她卡死了
        emit(
            feedback,
            ActionVisualFeedback.MacroReplayProgress(
                macroId = macroId,
                stepIndex = stepIndex + 1,
                totalSteps = totalSteps
            )
        )
        val before = fingerprintService.sampleOnce()
        val action = step.toCoordinatorAction(resolvedText)
        if (action == null) {
            MacroStepExecution(success = false, reason = "宏步骤无法转换为原子动作")
        } else {
            when (val outcome = executor.executeAtomic(action, ledger.steps + 1, budget.maxSteps)) {
                is AtomicExecutionOutcome.Succeeded,
                is AtomicExecutionOutcome.Completed -> {
                    val verification = fingerprintService.verifyAfterAction(before)
                    when (verification) {
                        is ActionVerification.EffectObserved ->
                            MacroStepExecution(success = true, verified = true)
                        is ActionVerification.NoVisibleEffect ->
                            // §5.2：任一步未生效立即中止回放、平滑降级为动态 ReAct
                            MacroStepExecution(success = false, reason = "单步核销未通过：页面无变化")
                        is ActionVerification.Unstable ->
                            MacroStepExecution(success = false, reason = "单步核销不可判定：页面持续震荡")
                        is ActionVerification.Unavailable ->
                            MacroStepExecution(success = false, reason = "单步核销不可用：页面不可读")
                    }
                }
                is AtomicExecutionOutcome.Blocked ->
                    MacroStepExecution(success = false, reason = outcome.reason, blockedRule = outcome.rule)
                is AtomicExecutionOutcome.Failed ->
                    MacroStepExecution(success = false, reason = outcome.reason)
                is AtomicExecutionOutcome.ConfirmationDeclined ->
                    MacroStepExecution(success = false, reason = outcome.reason)
                AtomicExecutionOutcome.Cancelled ->
                    MacroStepExecution(success = false, reason = "任务已取消")
            }
        }
    }

    // =====================================================================
    // 动态闭环主体
    // =====================================================================

    private suspend fun runDynamicLoop(
        request: AutonomousTaskRequest,
        ledger: BudgetLedger,
        builder: com.lirica.android.automation.trace.ExecutionTraceBuilder,
        mode: ActionExecutionMode
    ): AutonomousTaskResult {
        val maxSteps = request.maxSteps ?: budget.maxSteps
        val history = mutableListOf<StepRecord>()
        val replayableSteps = mutableListOf<MacroStep>()
        var startPageFingerprint: Long = 0L
        var lastMacroId: String? = null
        // 实际派发过动作的步数：与预算消耗量不同（收尾那一轮也消耗额度，但没有动作落地）
        var executedSteps = 0

        while (true) {
            // ---- 取消优先于一切：用户喊停就立刻停，不再派发任何动作 ----
            if (cancellationCheck()) {
                emit(feedback, ActionVisualFeedback.BubbleState(EmbodiedState.IDLE, "好，我停下了"))
                return AutonomousTaskResult.Cancelled
            }

            // ---- 预算：总耗时先于步数判定（超时是更硬的边界）----
            val deadline = ledger.checkDeadline()
            if (deadline is BudgetCheck.Exhausted) {
                return failWithNarration(deadline.reason, ledger.steps, TraceFinalState.BUDGET_EXHAUSTED)
            }
            val stepBudget = ledger.consumeStep()
            if (stepBudget is BudgetCheck.Exhausted) {
                return failWithNarration(stepBudget.reason, ledger.steps, TraceFinalState.BUDGET_EXHAUSTED)
            }
            val stepIndex = ledger.steps

            // ---- 用户抢占熔断（§6.2 / R4）----
            if (interruptionDetector.isUserActive()) {
                builder.recordInterruption()
                emit(feedback, ActionVisualFeedback.BubbleState(EmbodiedState.USER_INTERRUPTED, "你先用，我等着"))
                val cleared = interruptionDetector.awaitClearance(
                    interruptionAwaitMs.coerceAtMost(ledger.remainingMs() + 1L)
                )
                if (!cleared) {
                    return AutonomousTaskResult.UserInterrupted(
                        reason = "检测到你在操作手机，我先停下来了（等你点继续）",
                        stepIndex = stepIndex
                    )
                }
                // 用户续接后重试本步；被抢占的那一轮已经消耗过一次步数额度（计入预算更诚实）
                continue
            }
            interruptionDetector.refresh()

            // ---- Observe ----
            val observationResult = observer.observe(ledger, request.goal, budget)
            val observation = when (observationResult) {
                is ObservationResult.Success -> observationResult.snapshot
                is ObservationResult.Unavailable -> {
                    val healing = attemptSelfHeal(
                        ledger = ledger,
                        builder = builder,
                        observation = null,
                        actionSummary = "观测界面",
                        reason = observationResult.reason,
                        // 观测都拿不到页面，没有可用指纹（诚实传 0，而不是伪造一个）
                        pageFingerprint = 0L
                    )
                    if (healing is HealingOutcome.Exhausted) {
                        return failWithNarration(healing.reason, stepIndex, TraceFinalState.FAILED, healing.narrative)
                    }
                    continue
                }
            }
            if (observation.level == ObservationLevel.CLOUD_VLM) builder.recordVlmCall()

            // 降级通道 A：前台包名突变 = 疑似用户接管
            interruptionDetector.onForegroundObserved(observation.packageName)

            // ---- Decide ----
            val planned = planner.plan(
                PlanningContext(
                    goal = request.goal,
                    stepIndex = stepIndex,
                    maxSteps = maxSteps,
                    observation = observation,
                    history = history.toList(),
                    mode = mode
                )
            )
            if (planned == null) {
                val healing = attemptSelfHeal(
                    ledger = ledger,
                    builder = builder,
                    observation = observation,
                    actionSummary = "决策下一步",
                    reason = "决策层未能给出可执行指令"
                )
                if (healing is HealingOutcome.Exhausted) {
                    return failWithNarration(healing.reason, stepIndex, TraceFinalState.FAILED, healing.narrative)
                }
                continue
            }
            if (planned.finish) {
                lastMacroId = recordMacroIfEligible(request, observation, replayableSteps, startPageFingerprint)
                emit(feedback, ActionVisualFeedback.BubbleState(EmbodiedState.SUCCESS, planned.summary))
                return AutonomousTaskResult.Success(
                    message = planned.summary.ifBlank { "任务完成" },
                    steps = executedSteps,
                    macroId = lastMacroId
                )
            }
            val action = planned.action
            if (action == null) {
                return failWithNarration("决策层给出的步骤既无动作也未标记完成", stepIndex, TraceFinalState.FAILED)
            }

            emit(
                feedback,
                ActionVisualFeedback.StepProgress(
                    stepIndex = stepIndex,
                    totalSteps = maxSteps,
                    description = actionDescription(action)
                )
            )

            // ---- Act ----
            val before = fingerprintService.sampleOnce()
            if (startPageFingerprint == 0L && before != null) startPageFingerprint = before.hash
            val nodeStateBefore = probeNodeState(observation, planned.refId)

            // 我方即将主动切换前台应用：先登记，否则下一步的前台突变监测
            // 会把"她自己打开了应用"误判成"用户抢了手机"并熔断整个任务
            if (action is CoordinatorAction.Intent &&
                action.action is com.lirica.android.automation.intent.IntentAction.LaunchApp
            ) {
                val target = (action.action as com.lirica.android.automation.intent.IntentAction.LaunchApp).packageName
                interruptionDetector.onAutomationForegroundChange(target)
            }

            val startedAtMs = timeProvider()
            val outcome = executor.executeAtomic(action, stepIndex, maxSteps)
            val durationMs = (timeProvider() - startedAtMs).coerceAtLeast(0L)

            when (outcome) {
                is AtomicExecutionOutcome.Blocked -> {
                    builder.recordStep(
                        traceStep(stepIndex, action, observation, durationMs, startedAtMs, TraceStep.VerificationOutcome.NOT_APPLICABLE, blocked = true)
                    )
                    return AutonomousTaskResult.PolicyBlocked(
                        rule = outcome.rule,
                        reason = outcome.reason,
                        honestReason = outcome.honestNarrative ?: "这个操作超出我能替你做的范围",
                        negotiationHint = if (outcome.rule == ROAMING_RULE) "要不要我这次就破例？你说行我就去" else null
                    )
                }
                is AtomicExecutionOutcome.ConfirmationDeclined -> {
                    builder.recordStep(
                        traceStep(stepIndex, action, observation, durationMs, startedAtMs, TraceStep.VerificationOutcome.NOT_APPLICABLE)
                    )
                    return AutonomousTaskResult.Failure(outcome.reason, stepIndex, finalState = TraceFinalState.POLICY_BLOCKED)
                }
                AtomicExecutionOutcome.Cancelled -> return AutonomousTaskResult.Cancelled
                is AtomicExecutionOutcome.Completed -> {
                    lastMacroId = recordMacroIfEligible(request, observation, replayableSteps, startPageFingerprint)
                    return AutonomousTaskResult.Success(
                        message = outcome.summary,
                        steps = stepIndex,
                        macroId = lastMacroId
                    )
                }
                is AtomicExecutionOutcome.Failed -> {
                    builder.recordStep(
                        traceStep(stepIndex, action, observation, durationMs, startedAtMs, TraceStep.VerificationOutcome.NOT_APPLICABLE)
                    )
                    val healing = attemptSelfHeal(
                        ledger = ledger,
                        builder = builder,
                        observation = observation,
                        actionSummary = actionDescription(action),
                        reason = outcome.reason,
                        honestNarrative = outcome.honestNarrative,
                        pageFingerprint = before?.hash ?: 0L
                    )
                    if (healing is HealingOutcome.Exhausted) {
                        return failWithNarration(healing.reason, stepIndex, TraceFinalState.FAILED, healing.narrative)
                    }
                    continue
                }
                is AtomicExecutionOutcome.Succeeded -> Unit
            }

            // ---- Verify（双采样抗噪核销）----
            val verification = fingerprintService.verifyAfterAction(
                before = before,
                nodeProbe = { probeNodeState(observation, planned.refId) },
                nodeStateBefore = nodeStateBefore
            )
            executedSteps = stepIndex
            builder.recordStep(
                traceStep(stepIndex, action, observation, durationMs, startedAtMs, verification.toOutcome())
            )
            history.add(StepRecord(stepIndex, actionDescription(action), verification, planned.expectation))
            toMacroStep(action, observation, planned.refId, request.slots)?.let { replayableSteps.add(it) }

            if (verification is ActionVerification.NoVisibleEffect) {
                val healing = attemptSelfHeal(
                    ledger = ledger,
                    builder = builder,
                    observation = observation,
                    actionSummary = actionDescription(action),
                    reason = "动作已派发但页面未见变化",
                    pageFingerprint = verification.after?.hash ?: 0L
                )
                if (healing is HealingOutcome.Exhausted) {
                    return failWithNarration(healing.reason, stepIndex, TraceFinalState.FAILED, healing.narrative)
                }
                continue
            }

            if (stepIndex >= maxSteps) {
                return failWithNarration(
                    "达到最大执行步数上限 (" + maxSteps + " 步)",
                    stepIndex,
                    TraceFinalState.BUDGET_EXHAUSTED
                )
            }
        }
    }

    // =====================================================================
    // 自愈
    // =====================================================================

    private sealed interface HealingOutcome {
        data object Recovered : HealingOutcome
        data class Exhausted(val reason: String, val narrative: String?) : HealingOutcome
    }

    private suspend fun attemptSelfHeal(
        ledger: BudgetLedger,
        builder: com.lirica.android.automation.trace.ExecutionTraceBuilder,
        observation: ObservationSnapshot?,
        actionSummary: String,
        reason: String,
        honestNarrative: String? = null,
        /**
         * 当前页面指纹。
         *
         * 必须传真实值：watchdog 的 STUCK_LOOP（同一页面反复出现）检测完全依赖它，
         * 恒传 0 等于把"卡死自愈"这条能力关掉——而它恰恰是自愈里最有用的一条。
         */
        pageFingerprint: Long = 0L
    ): HealingOutcome {
        val verdict = watchdog.inspectStep(
            fingerprint = pageFingerprint,
            currentPackage = observation?.packageName ?: (currentPackageProvider() ?: ""),
            targetPackage = null,
            actionSummary = actionSummary,
            pageTexts = observation?.nodes?.mapNotNull { it.text ?: it.contentDescription } ?: emptyList()
        )

        if (verdict is WatchdogVerdict.Unrecoverable) {
            return HealingOutcome.Exhausted(verdict.reason, verdict.honestNarrative ?: honestNarrative)
        }

        val healBudget = ledger.consumeSelfHeal()
        if (healBudget is BudgetCheck.Exhausted) {
            val narrative = honestNarrator.narrate(
                FailureContext(
                    scenario = FailureScenario.SELF_HEALING_EXHAUSTED,
                    technicalReason = healBudget.reason + "；最后一次异常：" + reason,
                    targetPackage = currentPackageProvider()
                )
            )
            return HealingOutcome.Exhausted(healBudget.reason, narrative)
        }
        builder.recordSelfHeal()

        if (verdict is WatchdogVerdict.Recoverable) {
            watchdog.recordRecoveryAttempt(verdict.anomaly)
            emit(
                feedback,
                ActionVisualFeedback.BubbleState(EmbodiedState.OBSERVING, "有点卡住了，我换个法子试试")
            )
            applyRecovery(verdict.suggestedAction, observation)
        }
        return HealingOutcome.Recovered
    }

    /**
     * 自愈动作落地。
     *
     * 刻意复用 [AtomicActionExecutor] 而不是直接调手势执行器：
     * 自愈点击**同样**要过安全闸门与双态模式管辖，否则"关个弹窗"
     * 就成了绕过安全收口的后门。
     */
    private suspend fun applyRecovery(action: HealingAction, observation: ObservationSnapshot?) {
        when (action) {
            is HealingAction.DismissPopup -> {
                val keyword = action.targetKeyword
                val candidate = observation?.nodes?.firstOrNull { node ->
                    val label = node.text ?: node.contentDescription
                    label != null && (keyword == null || label.contains(keyword))
                }
                if (candidate != null) {
                    executor.executeAtomic(
                        CoordinatorAction.Click(
                            query = GroundingQuery(
                                text = candidate.text,
                                resourceId = candidate.resourceId,
                                semanticDescription = candidate.contentDescription,
                                normalizedAnchorX = candidate.normalizedX,
                                normalizedAnchorY = candidate.normalizedY
                            ),
                            description = "关闭弹窗"
                        ),
                        stepIndex = 0,
                        totalSteps = 1
                    )
                }
            }
            is HealingAction.PhysicalBacktrack -> {
                executor.executeAtomic(CoordinatorAction.Backtrack(), stepIndex = 0, totalSteps = 1)
            }
            is HealingAction.ReGround -> Unit
            is HealingAction.EscalateToUser -> Unit
        }
    }

    // =====================================================================
    // 宏录制
    // =====================================================================

    private suspend fun recordMacroIfEligible(
        request: AutonomousTaskRequest,
        observation: ObservationSnapshot,
        steps: List<MacroStep>,
        startPageFingerprint: Long
    ): String? {
        val memory = trajectoryMemory ?: return null
        val signature = request.intentSignature ?: return null
        if (steps.isEmpty()) return null
        val appPackage = observation.packageName.ifBlank { currentPackageProvider() ?: "" }
        if (appPackage.isBlank()) return null
        // M6.0 P1-7：CancellationException 原样上抛——宏录制落库被取消时不得
        // 被粉饰成"录制失败返回 null"（那会误导调用方走失败分支而非终止）
        val recorded = try {
            memory.record(
                MacroRecordSpec(
                    intentSignature = signature,
                    appPackage = appPackage,
                    targetAppVersionCode = appVersionCodeProvider(appPackage),
                    startPageFingerprint = startPageFingerprint,
                    steps = steps
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Throwable) {
            LiricaLog.w("AutonomousActionLoop", "经验轨迹入库失败", t)
            null
        } ?: return null
        LiricaLog.i("AutonomousActionLoop", "经验轨迹已入库：" + signature + " @ " + appPackage + " (" + steps.size + " 步)")
        return recorded.macroId
    }

    private fun toMacroStep(
        action: CoordinatorAction,
        observation: ObservationSnapshot,
        refId: String?,
        slots: Map<String, String>
    ): MacroStep? {
        val node = refId?.let { observation.nodeByRef(it) }
        return when (action) {
            is CoordinatorAction.Click -> MacroStep(
                kind = MacroStepKind.CLICK,
                refId = refId,
                normalizedX = node?.normalizedX,
                normalizedY = node?.normalizedY,
                description = action.description
            )
            is CoordinatorAction.Type -> {
                val slotKey = slots.entries.firstOrNull { it.value == action.text }?.key
                MacroStep(
                    kind = MacroStepKind.TYPE,
                    refId = refId,
                    normalizedX = node?.normalizedX,
                    normalizedY = node?.normalizedY,
                    text = if (slotKey == null) action.text else null,
                    slotKey = slotKey,
                    description = action.description
                )
            }
            is CoordinatorAction.Swipe -> MacroStep(
                kind = MacroStepKind.SCROLL,
                direction = action.direction.name.lowercase(),
                description = action.description
            )
            is CoordinatorAction.Backtrack -> MacroStep(kind = MacroStepKind.BACK, description = action.description)
            is CoordinatorAction.Delay -> MacroStep(
                kind = MacroStepKind.WAIT,
                delayMs = action.delayMs,
                description = action.description
            )
            is CoordinatorAction.PressKey -> MacroStep(
                kind = MacroStepKind.KEY,
                keyCode = action.keyCode,
                description = action.description
            )
            is CoordinatorAction.Intent, is CoordinatorAction.Finish -> null
        }
    }

    private fun MacroStep.toCoordinatorAction(resolvedText: String?): CoordinatorAction? {
        return when (kind) {
        MacroStepKind.CLICK -> CoordinatorAction.Click(
            query = GroundingQuery(
                text = null,
                resourceId = null,
                semanticDescription = description.ifBlank { null },
                normalizedAnchorX = normalizedX,
                normalizedAnchorY = normalizedY
            ),
            description = description.ifBlank { "回放点击" }
        )
        MacroStepKind.TYPE -> {
            val text = resolvedText ?: text ?: return null
            CoordinatorAction.Type(
                query = GroundingQuery(
                    semanticDescription = description.ifBlank { null },
                    normalizedAnchorX = normalizedX,
                    normalizedAnchorY = normalizedY
                ),
                text = text,
                description = description.ifBlank { "回放输入" }
            )
        }
        MacroStepKind.SCROLL -> CoordinatorAction.Swipe(
            direction = when (direction?.lowercase()) {
                "down" -> SwipeDirection.DOWN
                "left" -> SwipeDirection.LEFT
                "right" -> SwipeDirection.RIGHT
                else -> SwipeDirection.UP
            }
        )
        MacroStepKind.BACK -> CoordinatorAction.Backtrack()
            MacroStepKind.WAIT -> CoordinatorAction.Delay(delayMs ?: 500L)
            MacroStepKind.KEY -> CoordinatorAction.PressKey(keyCode = keyCode ?: return null)
        }
    }

    // =====================================================================
    // 工具方法
    // =====================================================================

    private suspend fun probeNodeState(observation: ObservationSnapshot, refId: String?): NodeStateToken? {
        if (refId == null) return null
        val node = observation.nodeByRef(refId) ?: return null
        return NodeStateToken(
            enabled = node.isEnabled,
            checked = node.isChecked,
            selected = false,
            text = node.text
        )
    }

    private fun traceStep(
        index: Int,
        action: CoordinatorAction,
        observation: ObservationSnapshot,
        durationMs: Long,
        startedAtMs: Long,
        outcome: TraceStep.VerificationOutcome,
        blocked: Boolean = false
    ): TraceStep = TraceStep(
        index = index,
        description = actionDescription(action),
        observationLevel = observation.level,
        actionKind = action::class.simpleName ?: "Action",
        startedAtMs = startedAtMs,
        durationMs = durationMs,
        verification = outcome,
        blockedByPolicy = blocked
    )

    private fun ActionVerification.toOutcome(): TraceStep.VerificationOutcome = when (this) {
        is ActionVerification.EffectObserved -> TraceStep.VerificationOutcome.EFFECT_OBSERVED
        is ActionVerification.NoVisibleEffect -> TraceStep.VerificationOutcome.NO_VISIBLE_EFFECT
        is ActionVerification.Unstable -> TraceStep.VerificationOutcome.UNSTABLE
        is ActionVerification.Unavailable -> TraceStep.VerificationOutcome.UNAVAILABLE
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

    private fun emit(listener: ActionVisualFeedbackListener, event: ActionVisualFeedback) {
        runCatching { listener.onFeedback(event) }
    }

    private fun failWithNarration(
        reason: String,
        stepIndex: Int,
        finalState: TraceFinalState,
        narrative: String? = null
    ): AutonomousTaskResult {
        val honest = narrative ?: honestNarrator.narrate(
            FailureContext(
                scenario = FailureScenario.SELF_HEALING_EXHAUSTED,
                technicalReason = reason,
                targetPackage = currentPackageProvider()
            )
        )
        emit(feedback, ActionVisualFeedback.BubbleState(EmbodiedState.FAILED, honest))
        return AutonomousTaskResult.Failure(reason, stepIndex, honest, finalState)
    }

    companion object {
        /** 用户抢占后的等待上限：默认 60 秒，随后交由心智层决定是否继续 */
        const val DEFAULT_INTERRUPTION_AWAIT_MS: Long = 60_000L

        /** 漫游只读规则 ID（引用安全目录常量，杜绝两处字面量漂移） */
        val ROAMING_RULE: String = com.lirica.android.automation.policy.SafetyRuleIds.ROAMING_READ_ONLY_BLOCKED
    }
}
