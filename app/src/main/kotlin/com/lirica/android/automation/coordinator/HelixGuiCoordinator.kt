package com.lirica.android.automation.coordinator

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.lirica.android.automation.backend.ExecutionBackend
import com.lirica.android.automation.capture.ScreenCaptureManager
import com.lirica.android.automation.feedback.ActionFeedbackHub
import com.lirica.android.automation.feedback.ActionVisualFeedback
import com.lirica.android.automation.feedback.ActionVisualFeedbackListener
import com.lirica.android.automation.feedback.EmbodiedState
import com.lirica.android.automation.feedback.NoOpActionVisualFeedbackListener
import com.lirica.android.automation.feedback.TouchKind
import com.lirica.android.automation.gatekeeper.FinancialGatekeeper
import com.lirica.android.automation.gatekeeper.GatekeeperDecision
import com.lirica.android.automation.gatekeeper.ProhibitedZones
import com.lirica.android.automation.gatekeeper.ProposedAction
import com.lirica.android.automation.gesture.GestureExecutor
import com.lirica.android.automation.gesture.GestureResult
import com.lirica.android.automation.gesture.GestureSpec
import com.lirica.android.automation.gesture.TextInputExecutor
import com.lirica.android.automation.grounding.ElementBounds
import com.lirica.android.automation.grounding.EmptyReason
import com.lirica.android.automation.grounding.ExecutionNode
import com.lirica.android.automation.grounding.ExecutionTreeExtractor
import com.lirica.android.automation.grounding.GroundingQuery
import com.lirica.android.automation.grounding.GroundingResult
import com.lirica.android.automation.grounding.HybridGroundingEngine
import com.lirica.android.automation.grounding.TreeDumpResult
import com.lirica.android.automation.grounding.toUiElementNodes
import com.lirica.android.automation.healing.FailureContext
import com.lirica.android.automation.healing.FailureScenario
import com.lirica.android.automation.healing.HealingAction
import com.lirica.android.automation.healing.HonestNarrator
import com.lirica.android.automation.healing.HonestNarratorImpl
import com.lirica.android.automation.healing.SelfHealingWatchdog
import com.lirica.android.automation.healing.SelfHealingWatchdogImpl
import com.lirica.android.automation.healing.WatchdogVerdict
import com.lirica.android.automation.intent.DispatchResult
import com.lirica.android.automation.intent.IntentActionDispatcher
import com.lirica.android.automation.policy.ActionExecutionMode
import com.lirica.android.automation.policy.SafetyDecision
import com.lirica.android.automation.policy.SafetyGrant
import com.lirica.android.automation.policy.SafetyPolicyEngine
import com.lirica.android.automation.policy.SafetyRequest
import com.lirica.android.automation.policy.SafetyRuleIds
import com.lirica.android.automation.policy.SystemActionCatalog
import com.lirica.android.common.logging.LiricaLog
import com.lirica.android.perception.AccessibilityServiceHolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

interface HelixGuiCoordinator {
    val state: StateFlow<TaskExecutionState>

    suspend fun executeTask(
        goal: String,
        actions: List<CoordinatorAction>,
        maxSteps: Int = MAX_STEPS
    ): TaskExecutionResult

    /**
     * M5.2 单步动态 ReAct 闭环入口（主方案5.0 §5.1）。
     *
     * 与 [executeTask] 的关系：后者执行**已知的**动作序列（聊天层已规划好），
     * 前者每步重新观测并决策，用于"用户只说目标、步骤由她自己摸索"的场景。
     * 两条路径共用同一个原子动作落地通道（[AtomicActionExecutor]），
     * 不存在第二套执行引擎（P1-1 双决策引擎风险的封堵方式）。
     */
    suspend fun executeAutonomousTask(request: AutonomousTaskRequest): TaskExecutionResult

    /** 当前双态模式（共享 / 漫游只读） */
    fun currentMode(): ActionExecutionMode

    /** 切换双态模式（UI 开关或对话决策控制） */
    fun setMode(mode: ActionExecutionMode)

    /** 漫游模式下经用户对话协商后临时提升单次任务作用域（§八 自主风险交互） */
    fun elevateRoamingScope(taskId: String): String

    fun confirmPendingAction(token: String): Boolean
    fun cancelCurrentTask()

    /** 注册具身视觉反馈监听（悬浮窗或具身渲染层接入） */
    fun registerFeedbackListener(listener: com.lirica.android.automation.feedback.ActionVisualFeedbackListener)

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
    // （LiricaApplication 用 DisplayManager 物理显示读取）；provider 返回 0 时
    // 接地引擎会明确返回 NotFound，故障暴露在日志与自愈链路里。
    private val screenWidthProvider: () -> Int = { 0 },
    private val screenHeightProvider: () -> Int = { 0 },
    private val screenCaptureManager: ScreenCaptureManager? = null,
    private val executionBackend: ExecutionBackend? = null,
    private val confirmationTimeoutMs: Long = 300_000L,
    private val honestNarrator: HonestNarrator = HonestNarratorImpl(),
    /**
     * 统一安全裁决引擎（M5.1 P0-1 / 复审 R7）。
     *
     * 为 null 时退化为"仅 FinancialGatekeeper"的历史行为，以保证既有单测零改动；
     * 生产装配（LiricaApplication）必须注入真实引擎，并由 ApplicationWiringTest
     * 断言，杜绝"忘了注入"变成静默降权。
     */
    private val safetyPolicy: SafetyPolicyEngine? = null,
    /** 具身视觉反馈监听（M5.1 具身方案A：接口先行，悬浮窗后续接入） */
    private val feedbackListener: ActionVisualFeedbackListener = NoOpActionVisualFeedbackListener,
    /** 单步动态闭环（未装配时 executeAutonomousTask 明确失败，绝不静默假装成功） */
    private val autonomousLoop: AutonomousActionLoop? = null,
    /**
     * 共享取消信号。
     *
     * 由装配层创建并同时交给协调器与动态闭环：用户在对话/悬浮窗喊停时，
     * 闭环必须**立刻停止派发**，而不是只把状态显示改成"已取消"却继续把手势打完。
     */
    private val cancelSignal: java.util.concurrent.atomic.AtomicBoolean? = null,
    /**
     * 无障碍可用性前置探测（§7.1 第 2 条：任务分发前快速检测无障碍状态）。
     *
     * 默认 true 以保持既有单测零改动；生产装配注入真实探测（见 LiricaApplication）。
     */
    private val accessibilityReadyProvider: () -> Boolean = { true },
    /** 视觉重通道闸门（§7.3：温度/电量熔断时禁止截屏与云端 VLM） */
    private val visionAllowedProvider: () -> Boolean = { true },
    /**
     * 用户抢占熔断器（M6.0 P1-2 接线：自触发抑制 + 前台突变豁免）。
     *
     * 历史缺陷：该检测器的抑制登记 API 生产端零调用——闭环每派发一个手势，
     * 都会把引擎自己的手势判成用户抢机，awaitClearance(60s) 超时后任务自断。
     * 本协调器在所有派发点接线登记；null = 不接线（既有单测零改动）。
     */
    private val userInterruptionDetector: com.lirica.android.automation.verify.UserInterruptionDetector? = null
) : HelixGuiCoordinator, AtomicActionExecutor {

    private val _state = MutableStateFlow<TaskExecutionState>(TaskExecutionState.Idle)
    override val state: StateFlow<TaskExecutionState> = _state.asStateFlow()

    private val feedbackHub = (feedbackListener as? ActionFeedbackHub) ?: ActionFeedbackHub(listOf(feedbackListener))

    private val isCancelled: AtomicBoolean = cancelSignal ?: AtomicBoolean(false)

    /**
     * 任务级互斥（M6.0 B2 收口）。
     *
     * 历史缺陷：executeTask 与 executeAutonomousTask 两条入口共享同一个
     * `_state`/isCancelled/watchdog 且入口处都会 `set(false)`——后到任务会清掉
     * 前一个任务的取消请求，状态机与自愈步历史交错。目前靠"聊天层工具调用
     * 是顺序的"这一隐式约定保证安全，但悬浮窗与对话两条链路理论上可并发
     * 触发。互斥后并发发起的第二个任务会在第一个终止前排队的语义下串行执行，
     * 取消信号与状态机不再串扰。
     */
    private val taskMutex = kotlinx.coroutines.sync.Mutex()

    companion object {
        private const val TAG = "HelixGuiCoordinator"

        /** 文本注入前活节点树扫描上限，防止巨型 UI 树拖垮注入时延 */
        private const val MAX_LIVE_NODE_SCAN = 1000

        /** 闸门裁决用的键事件动作类型（映射 SystemActionCatalog.ID_UI_KEY_EVENT） */
        private const val ACTION_TYPE_KEY_EVENT = "KEY_EVENT"

        /**
         * 确认类按键集合：这些键会激活当前焦点元素（按钮/复选框），
         * 是"盲按回车点到支付确认默认按钮"风险的载体，需要叠加专项裁定。
         */
        private val CONFIRMATION_KEY_CODES = setOf(
            android.view.KeyEvent.KEYCODE_ENTER,
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
            android.view.KeyEvent.KEYCODE_DPAD_CENTER
        )
    }

    // =====================================================================
    // 双态模式（M5.1 §八）
    // =====================================================================

    override fun currentMode(): ActionExecutionMode =
        safetyPolicy?.mode?.value ?: ActionExecutionMode.SHARED

    override fun setMode(mode: ActionExecutionMode) {
        val engine = safetyPolicy
            ?: throw IllegalStateException("未装配 SafetyPolicyEngine，无法切换双态模式")
        engine.setMode(mode)
        emit(
            ActionVisualFeedback.BubbleState(
                state = EmbodiedState.IDLE,
                speech = if (mode == ActionExecutionMode.ROAMING) "我只看看，不动手" else "好，交给我"
            )
        )
    }

    override fun elevateRoamingScope(taskId: String): String {
        val engine = safetyPolicy
            ?: throw IllegalStateException("未装配 SafetyPolicyEngine，无法提升漫游作用域")
        return engine.elevateScopeForTask(taskId)
    }

    override suspend fun executeTask(
        goal: String,
        actions: List<CoordinatorAction>,
        maxSteps: Int
    ): TaskExecutionResult = taskMutex.withLock {
        executeTaskLocked(goal, actions, maxSteps)
    }

    private suspend fun executeTaskLocked(
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

        // §7.1：需要无障碍的动作在派发前先探测服务可用性。
        // 纯 Intent 动作（闹钟/日历/手电筒）不依赖无障碍，不应被这条门禁误伤——
        // "无障碍被系统关了就连闹钟都设不了"是过度耦合。
        if (actions.any { it.requiresAccessibility() } && !accessibilityReadyProvider()) {
            val reason = "无障碍服务当前不可用（可能被系统或省电策略关闭）"
            val narrative = "我的无障碍服务好像被系统关掉了，现在碰不到屏幕。" +
                "你到「设置 - 无障碍」里帮我重新打开一下，我马上就能继续。"
            _state.value = TaskExecutionState.Failed(reason, 0, narrative)
            return TaskExecutionResult.Failure(reason, 0, narrative)
        }

        // L0 极速通道：单步标准 Intent 预检直达 (≤15ms)
        if (actions.size == 1 && actions[0] is CoordinatorAction.Intent) {
            val intentAction = (actions[0] as CoordinatorAction.Intent).action
            return when (val dispatchResult = intentDispatcher.dispatch(intentAction)) {
                is DispatchResult.Success -> {
                    _state.value = TaskExecutionState.Completed("系统原生意图直达成功", 1)
                    TaskExecutionResult.Success("系统原生意图直达成功", 1)
                }
                // P0 缺陷修复：中危 Intent（短信/邮件/分享文本）与缺权限动作在极速通道里
                // 也必须能走完"二次确认 / 引导授权"闭环。早期实现只认 Success，
                // 会把 SendSms 的确认请求吞成一句"意图分发失败"——闸门装好了，人却过不去，
                // 用户既看不到确认卡片，也不知道究竟差在哪。
                is DispatchResult.ConfirmationRequired, is DispatchResult.PermissionRequired -> {
                    when (val outcome = dispatchIntentWithGate(intentAction, 1)) {
                        is AtomicExecutionOutcome.Succeeded -> {
                            _state.value = TaskExecutionState.Completed("系统原生意图直达成功", 1)
                            TaskExecutionResult.Success("系统原生意图直达成功", 1)
                        }
                        is AtomicExecutionOutcome.Completed ->
                            TaskExecutionResult.Success(outcome.summary, 1)
                        is AtomicExecutionOutcome.Blocked ->
                            TaskExecutionResult.Failure(outcome.reason, 1, outcome.honestNarrative)
                        is AtomicExecutionOutcome.Failed ->
                            TaskExecutionResult.Failure(outcome.reason, 1, outcome.honestNarrative)
                        is AtomicExecutionOutcome.ConfirmationDeclined ->
                            TaskExecutionResult.Failure(outcome.reason, 1)
                        AtomicExecutionOutcome.Cancelled -> TaskExecutionResult.Cancelled
                    }
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
                val narrative = honestNarrator.narrate(
                    FailureContext(
                        scenario = FailureScenario.SELF_HEALING_EXHAUSTED,
                        technicalReason = reason,
                        targetPackage = currentPackageProvider()
                    )
                )
                _state.value = TaskExecutionState.Failed(reason, stepIndex, honestNarrative = narrative)
                return TaskExecutionResult.Failure(reason, stepIndex, honestNarrative = narrative)
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

            when (val outcome = dispatchSingleAction(action, stepIndex, actions.size)) {
                is AtomicExecutionOutcome.Succeeded -> Unit

                is AtomicExecutionOutcome.Completed -> {
                    _state.value = TaskExecutionState.Completed(outcome.summary, stepIndex)
                    return TaskExecutionResult.Success(outcome.summary, stepIndex)
                }

                is AtomicExecutionOutcome.Blocked -> {
                    // 单步分派失败时已把**更精确**的 errorReason 与自愈叙述落到 _state
                    // （例如"零信任高危护栏物理熔断: xxx (RULE_xxx)"）。这里必须优先沿用，
                    // 否则会把精确原因覆盖成笼统结论，用户话术与回归断言同时退化。
                    val failedState = _state.value as? TaskExecutionState.Failed
                    val reason = failedState?.errorReason ?: outcome.reason
                    val narrative = failedState?.honestNarrative ?: outcome.honestNarrative
                    if (failedState == null) {
                        _state.value = TaskExecutionState.Failed(reason, stepIndex, narrative)
                    }
                    return TaskExecutionResult.Failure(reason, stepIndex, narrative)
                }

                is AtomicExecutionOutcome.Failed -> {
                    val failedState = _state.value as? TaskExecutionState.Failed
                    val reason = failedState?.errorReason ?: outcome.reason
                    val narrative = failedState?.honestNarrative ?: outcome.honestNarrative
                    if (failedState == null) {
                        _state.value = TaskExecutionState.Failed(reason, stepIndex, narrative)
                    }
                    return TaskExecutionResult.Failure(reason, stepIndex, narrative)
                }

                is AtomicExecutionOutcome.ConfirmationDeclined -> {
                    _state.value = TaskExecutionState.Failed(outcome.reason, stepIndex)
                    return TaskExecutionResult.Failure(outcome.reason, stepIndex)
                }

                AtomicExecutionOutcome.Cancelled -> {
                    _state.value = TaskExecutionState.Idle
                    return TaskExecutionResult.Cancelled
                }
            }
        }

        val completedMsg = "任务代行执行完毕"
        _state.value = TaskExecutionState.Completed(completedMsg, stepIndex)
        return TaskExecutionResult.Success(completedMsg, stepIndex)
    }

    // =====================================================================
    // 原子动作分派（静态序列与动态闭环共用同一通道）
    // =====================================================================

    /**
     * 原子动作执行入口（[AtomicActionExecutor] 契约实现）。
     *
     * 动态闭环的每一步都经此落地，因此**安全闸门、自愈、具身反馈与静态序列
     * 完全同源**——不存在"动态路径绕过闸门"的结构性缺口。
     */
    override suspend fun executeAtomic(
        action: CoordinatorAction,
        stepIndex: Int,
        totalSteps: Int
    ): AtomicExecutionOutcome {
        _state.value = TaskExecutionState.ExecutingStep(
            stepIndex = stepIndex,
            totalStepsEstimate = totalSteps,
            actionDescription = actionDescription(action),
            isRecovering = false
        )
        return dispatchSingleAction(action, stepIndex, totalSteps)
    }

    private suspend fun dispatchSingleAction(
        action: CoordinatorAction,
        stepIndex: Int,
        totalSteps: Int
    ): AtomicExecutionOutcome {
        // M6.0 P1-2：登记前台突变豁免窗口——我方派发的任何动作都可能合法切换前台
        // （LaunchApp、点击跨应用链接、HOME 键、系统权限对话框弹出）。豁免期内的
        // 包名突变只刷新基线不判接管，否则跨应用任务必被 FOREGROUND_TAKEOVER 误杀。
        userInterruptionDetector?.onAutomationForegroundChangeExpected()
        return when (action) {
        is CoordinatorAction.Finish -> AtomicExecutionOutcome.Completed(action.summary)

        is CoordinatorAction.Intent -> dispatchIntentWithGate(action.action, stepIndex)

        is CoordinatorAction.Backtrack -> {
            serviceHolder.get()?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            AtomicExecutionOutcome.Succeeded(action.description)
        }

        is CoordinatorAction.PressKey -> dispatchPressKeyWithGate(action, stepIndex)

        is CoordinatorAction.Delay -> {
            kotlinx.coroutines.delay(action.delayMs.coerceAtLeast(0L))
            AtomicExecutionOutcome.Succeeded(action.description)
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
            emitTouch(startX, startY, endX, endY, 300L, TouchKind.SWIPE)
            // M6.0 P1-2：触控抑制窗口——本手势产生的触控事件是自产，不算用户抢机
            userInterruptionDetector?.onAutomationGestureDispatched(300L)
            // M6.0 P3-3：backend 返回值纳入 outcome——静默失败的 input swipe 不能谎报成功
            if (executionBackend != null && executionBackend.isAvailable()) {
                val backendOk = executionBackend.swipe(startX, startY, endX, endY, 300L)
                if (!backendOk) {
                    val reason = "滑动派发失败（执行通道返回失败）"
                    _state.value = TaskExecutionState.Failed(reason, stepIndex)
                    return AtomicExecutionOutcome.Failed(reason)
                }
            } else {
                when (val gestureResult = gestureExecutor.execute(
                    GestureSpec.Swipe(startX, startY, endX, endY, humanoidCurve = action.humanoid)
                )) {
                    is GestureResult.Completed -> Unit
                    is GestureResult.Cancelled -> {
                        if (gestureResult.reason.contains("受限") || gestureResult.reason.contains("blocked")) {
                            val narrative = honestNarrator.narrate(
                                FailureContext(
                                    rule = "RULE_GESTURE_PHYSICAL_BLOCKED",
                                    technicalReason = gestureResult.reason,
                                    targetPackage = currentPackageProvider(),
                                    actionType = "SWIPE",
                                    targetElement = null
                                )
                            )
                            _state.value = TaskExecutionState.Failed(
                                errorReason = "零信任高危护栏物理熔断: ${gestureResult.reason}",
                                lastStepIndex = stepIndex,
                                honestNarrative = narrative
                            )
                            return AtomicExecutionOutcome.Blocked("RULE_GESTURE_PHYSICAL_BLOCKED", gestureResult.reason, narrative)
                        }
                        val reason = "滑动手势被系统取消: ${gestureResult.reason}"
                        _state.value = TaskExecutionState.Failed(reason, stepIndex)
                        return AtomicExecutionOutcome.Failed(reason)
                    }
                    is GestureResult.TimedOut -> {
                        val reason = "滑动手势派发超时 (${gestureResult.timeoutMs}ms)"
                        _state.value = TaskExecutionState.Failed(reason, stepIndex)
                        return AtomicExecutionOutcome.Failed(reason)
                    }
                    is GestureResult.Error -> {
                        val reason = "滑动手势派发异常: ${gestureResult.throwable.message}"
                        _state.value = TaskExecutionState.Failed(reason, stepIndex)
                        return AtomicExecutionOutcome.Failed(reason)
                    }
                    GestureResult.ServiceUnavailable -> {
                        val reason = "无障碍服务不可用，无法派发滑动手势"
                        _state.value = TaskExecutionState.Failed(reason, stepIndex)
                        return AtomicExecutionOutcome.Failed(reason)
                    }
                }
            }
            AtomicExecutionOutcome.Succeeded(action.description)
        }

        is CoordinatorAction.Click, is CoordinatorAction.Type -> {
            val query = if (action is CoordinatorAction.Click) action.query else (action as CoordinatorAction.Type).query
            executeUiElementAction(action, query, stepIndex, totalSteps)
        }
        }
    }

    /**
     * 键事件通道安全裁决（M6.0 / 体检报告 P0-1 收口）。
     *
     * 按键是唯一没有目标节点可供文本级审查的通道：ENTER/DPAD_CENTER 会激活
     * 当前焦点默认按钮——支付确认弹窗的默认按钮往往正是它，[com.lirica.android.automation.gatekeeper.ProhibitedZones]
     * 的文本级支付关键词规则因此被整体绕过。收口分四层，全部 fail-closed：
     * 1. **统一引擎包名级裁定**：金融/密码/敏感包对键事件同样全量熔断；
     * 2. **通信应用代发熔断**：确认类按键在微信/QQ 内视同自动发送消息
     *    （经 extraInfo.isTextInput 复用 gatekeeper 的 D8 Tier 0 规则）；
     * 3. **活窗口危险扫描**：确认类按键前扫描无障碍树——存在密码框、
     *    支付/系统提权关键词一律物理熔断；高危确认词（提交订单/清空等）
     *    同样熔断——键事件没有可视目标，弹确认卡片反而让用户困惑"确认什么"；
     * 4. **漫游只读**：无作用域提升时确认类按键整体熔断。
     *
     * 树不可读时跳过第 3 层（包名级裁定仍生效），残余风险由核销层兜底并写入
     * trace——比"树不可读就放行一切"诚实，比"不可读即熔断"可用。
     */
    private suspend fun dispatchPressKeyWithGate(
        action: CoordinatorAction.PressKey,
        stepIndex: Int
    ): AtomicExecutionOutcome {
        val pkg = currentPackageProvider() ?: ""
        val isConfirmationKey = action.keyCode in CONFIRMATION_KEY_CODES

        // 1) 统一引擎包名级裁定（金融/敏感包全量熔断对键事件同样生效）
        val proposed = ProposedAction(
            actionType = ACTION_TYPE_KEY_EVENT,
            targetText = null,
            targetResourceId = null,
            isPasswordTarget = false,
            currentPackage = pkg,
            // 确认类按键在通信应用内 = 自动发送消息：经 extraInfo 复用 D8 Tier 0 规则
            extraInfo = if (isConfirmationKey) mapOf("isTextInput" to "true") else emptyMap()
        )
        when (val decision = evaluateUiGate(proposed)) {
            is GatekeeperDecision.PhysicalBlocked ->
                return blockedKeyOutcome(action, decision.rule, decision.reason, decision.honestReason, stepIndex)
            // 键事件无目标文本，现有规则不会产出确认令牌；万一未来规则产出，fail-closed
            is GatekeeperDecision.RequireUserConfirmation ->
                return blockedKeyOutcome(
                    action,
                    rule = SafetyRuleIds.KEY_EVENT_CONFIRMATION_GATE,
                    reason = "键事件命中需确认规则: " + decision.promptMessage,
                    honestReason = "这个按键会触发敏感操作，我不盲按",
                    stepIndex = stepIndex
                )
            is GatekeeperDecision.Allowed -> Unit
        }

        if (isConfirmationKey) {
            // 3) 活窗口危险扫描（焦点密码框 / 支付与系统提权关键词 / 高危确认词）
            when (val danger = scanConfirmationKeyWindowDanger()) {
                is ConfirmationWindowDanger.PasswordFocused ->
                    return blockedKeyOutcome(
                        action,
                        rule = "RULE_ZERO_TOLERANCE_PASSWORD",
                        reason = "确认类按键被焦点/可见密码框熔断",
                        honestReason = ProhibitedZones.DEFAULT_HONEST_REASON,
                        stepIndex = stepIndex,
                        scenario = FailureScenario.PASSWORD_INPUT
                    )
                is ConfirmationWindowDanger.CriticalKeyword ->
                    return blockedKeyOutcome(
                        action,
                        rule = if (danger.system) "RULE_SYSTEM_PRIVILEGE_FUSE" else "RULE_CRITICAL_PAYMENT_FUSE",
                        reason = "确认类按键命中屏幕关键词「${danger.keyword}」",
                        honestReason = ProhibitedZones.DEFAULT_HONEST_REASON,
                        stepIndex = stepIndex,
                        scenario = if (danger.system) FailureScenario.SYSTEM_PRIVILEGE else FailureScenario.FINANCIAL_PROHIBITED
                    )
                is ConfirmationWindowDanger.HighRiskKeyword ->
                    return blockedKeyOutcome(
                        action,
                        rule = SafetyRuleIds.KEY_EVENT_CONFIRMATION_GATE,
                        reason = "确认类按键命中高危确认词「${danger.keyword}」",
                        honestReason = "屏幕上有「${danger.keyword}」这类按钮，回车没有长眼睛，" +
                            "我不想替你盲按——你点一下它，或者明确告诉我继续",
                        stepIndex = stepIndex
                    )
                null -> Unit
            }

            // 4) 漫游只读：无作用域提升时确认类按键整体熔断
            if (currentMode() == ActionExecutionMode.ROAMING && safetyPolicy?.activeElevationTaskId() == null) {
                return blockedKeyOutcome(
                    action,
                    rule = SafetyRuleIds.ROAMING_READ_ONLY_BLOCKED,
                    reason = "漫游只读模式下禁止确认类按键（ENTER/DPAD_CENTER 可触发目标应用动作）",
                    honestReason = "漫游模式我只看看不动手；要动的话你在对话里跟我说一声",
                    stepIndex = stepIndex
                )
            }
        }

        // 5) 通过全部闸门后按原逻辑落地
        val ok = if (executionBackend != null && executionBackend.isAvailable()) {
            executionBackend.sendKeycode(action.keyCode)
        } else {
            val globalAction = com.lirica.android.automation.backend.KeycodeMapper.toAccessibilityGlobalAction(action.keyCode)
            if (globalAction != null) {
                serviceHolder.get()?.performGlobalAction(globalAction) == true
            } else {
                false
            }
        }
        return if (ok) {
            AtomicExecutionOutcome.Succeeded(action.description)
        } else {
            AtomicExecutionOutcome.Failed("按键事件模拟失败: " + action.keyName + " (keyCode=" + action.keyCode + ")")
        }
    }

    /** 键事件熔断的统一出口：人设化坦白 + 状态落库 + Blocked 结果 */
    private fun blockedKeyOutcome(
        action: CoordinatorAction.PressKey,
        rule: String,
        reason: String,
        honestReason: String,
        stepIndex: Int,
        scenario: FailureScenario = FailureScenario.GENERIC_FALLBACK
    ): AtomicExecutionOutcome.Blocked {
        val narrative = honestNarrator.narrate(
            FailureContext(
                rule = rule,
                technicalReason = reason,
                targetPackage = currentPackageProvider(),
                actionType = ACTION_TYPE_KEY_EVENT,
                targetElement = "按键 ${action.keyName}",
                scenario = scenario
            )
        )
        _state.value = TaskExecutionState.Failed(
            errorReason = "按键通道熔断: $reason ($rule)",
            lastStepIndex = stepIndex,
            honestNarrative = narrative
        )
        LiricaLog.w(TAG, "PressKey blocked: rule=$rule key=${action.keyName}")
        return AtomicExecutionOutcome.Blocked(rule, reason, narrative)
    }

    /** 确认类按键的活窗口危险扫描（BFS，节点数受 [MAX_LIVE_NODE_SCAN] 约束） */
    private fun scanConfirmationKeyWindowDanger(): ConfirmationWindowDanger? {
        val root = rootNodeProvider() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var scanned = 0
        while (queue.isNotEmpty() && scanned < MAX_LIVE_NODE_SCAN) {
            val node = queue.removeFirst()
            scanned++
            val resourceId = runCatching { node.viewIdResourceName }.getOrNull() ?: ""
            val text = runCatching { node.text?.toString() }.getOrNull()
            val contentDesc = runCatching { node.contentDescription?.toString() }.getOrNull()
            val isPasswordNode = runCatching { node.isPassword }.getOrDefault(false)

            if (ProhibitedZones.isPasswordTarget(isPasswordNode, resourceId, text ?: "")) {
                return ConfirmationWindowDanger.PasswordFocused(resourceId)
            }
            for (candidate in listOfNotNull(text, contentDesc)) {
                ProhibitedZones.CRITICAL_PAYMENT_KEYWORDS.firstOrNull { candidate.contains(it, ignoreCase = true) }
                    ?.let { return ConfirmationWindowDanger.CriticalKeyword(it, system = false) }
                ProhibitedZones.CRITICAL_SYSTEM_KEYWORDS.firstOrNull { candidate.contains(it, ignoreCase = true) }
                    ?.let { return ConfirmationWindowDanger.CriticalKeyword(it, system = true) }
                ProhibitedZones.HIGH_RISK_CONFIRM_KEYWORDS.firstOrNull { candidate.contains(it, ignoreCase = true) }
                    ?.let { return ConfirmationWindowDanger.HighRiskKeyword(it) }
            }
            val childCount = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until childCount) {
                runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
            }
        }
        return null
    }

    private sealed interface ConfirmationWindowDanger {
        data class PasswordFocused(val nodeHint: String) : ConfirmationWindowDanger
        data class CriticalKeyword(val keyword: String, val system: Boolean) : ConfirmationWindowDanger
        data class HighRiskKeyword(val keyword: String) : ConfirmationWindowDanger
    }

    /**
     * Intent 通道分派 + 二次确认闭环（P0-1 收口的最后一环）。
     *
     * 即使 L0 极速通道也走本方法，因此没有"绕过闸门"的捷径：
     * 非幂等外部通信（预填草稿类）在 dispatch 返回 ConfirmationRequired 时，
     * 复用既有 [TaskExecutionState.AwaitingUserConfirmation] 状态流发起原生卡片确认，
     * 用户过目正文后才以动作绑定凭证二次派发。
     */
    private suspend fun dispatchIntentWithGate(
        intentAction: com.lirica.android.automation.intent.IntentAction,
        stepIndex: Int
    ): AtomicExecutionOutcome {
        val first = intentDispatcher.dispatch(intentAction)
        return when (first) {
            is DispatchResult.Success -> AtomicExecutionOutcome.Succeeded("系统意图已派发")

            is DispatchResult.ConfirmationRequired -> {
                val approved = awaitConfirmation(token = first.token, prompt = first.promptMessage)
                if (!approved) {
                    return AtomicExecutionOutcome.ConfirmationDeclined("用户取消或高危操作未获确认")
                }
                val actionId = SystemActionCatalog.describe(intentAction)?.actionId
                val second = intentDispatcher.dispatch(
                    intentAction,
                    grant = actionId?.let { SafetyGrant(first.token, it) }
                )
                when (second) {
                    is DispatchResult.Success -> AtomicExecutionOutcome.Succeeded("系统意图已确认派发")
                    else -> AtomicExecutionOutcome.Failed("意图分发失败: " + second)
                }
            }

            is DispatchResult.PermissionRequired -> {
                val reason = "缺少权限 " + first.permissionName + "：" + first.reason
                val narrative = honestNarrator.narrate(
                    FailureContext(
                        rule = "RULE_PERMISSION_REQUIRED",
                        technicalReason = reason,
                        targetPackage = currentPackageProvider(),
                        actionType = "INTENT"
                    )
                )
                _state.value = TaskExecutionState.Failed(reason, stepIndex, narrative)
                AtomicExecutionOutcome.Failed(reason, narrative)
            }

            else -> {
                _state.value = TaskExecutionState.Failed("意图分发失败: " + first, stepIndex)
                AtomicExecutionOutcome.Failed("意图分发失败: " + first)
            }
        }
    }

    /** 复用既有二次确认状态机（原生卡片 / 悬浮胶囊） */
    private suspend fun awaitConfirmation(token: String, prompt: String): Boolean {
        val confirmation = CompletableDeferred<Boolean>()
        _state.value = TaskExecutionState.AwaitingUserConfirmation(
            token = token,
            riskDetails = prompt,
            draftContent = null,
            draftTitle = null,
            onConfirmed = {
                approveToken(token)
                confirmation.complete(true)
            },
            onCancelled = { confirmation.complete(false) }
        )
        return try {
            withTimeout(confirmationTimeoutMs) { confirmation.await() }
        } catch (e: TimeoutCancellationException) {
            _state.value = TaskExecutionState.Failed("用户确认超时 (" + confirmationTimeoutMs + " ms)", 0)
            false
        } catch (e: CancellationException) {
            _state.value = TaskExecutionState.Idle
            throw e
        }
    }

    /** 令牌批准：统一走裁决引擎；引擎缺席时才直接落 Gatekeeper（历史兼容路径） */
    private fun approveToken(token: String) {
        val engine = safetyPolicy
        if (engine != null) {
            engine.confirm(token)
        } else {
            gatekeeper.confirmAction(token)
        }
    }

    /**
     * UI 通道安全裁决入口。
     *
     * 装配了 [SafetyPolicyEngine] 时走统一引擎（其内部仍委托既有
     * FinancialGatekeeper，并叠加漫游只读拦截）；未装配时保持历史行为。
     * 生产装配由 ApplicationWiringTest 断言引擎必须注入。
     */
    private fun evaluateUiGate(proposed: ProposedAction): GatekeeperDecision {
        val engine = safetyPolicy ?: return gatekeeper.evaluate(proposed)
        val decision = engine.evaluate(
            SafetyRequest.UiInteraction(
                actionType = proposed.actionType,
                targetText = proposed.targetText,
                targetResourceId = proposed.targetResourceId,
                isPasswordTarget = proposed.isPasswordTarget,
                currentPackage = proposed.currentPackage
            )
        )
        return when (decision) {
            is SafetyDecision.Allowed -> GatekeeperDecision.Allowed(decision.riskLevel)
            is SafetyDecision.RequireConfirmation -> GatekeeperDecision.RequireUserConfirmation(
                token = decision.token,
                promptMessage = decision.promptMessage,
                details = decision.details
            )
            is SafetyDecision.Blocked -> GatekeeperDecision.PhysicalBlocked(
                reason = decision.reason,
                rule = decision.rule,
                honestReason = decision.honestReason
            )
        }
    }

    // =====================================================================
    // 动态闭环委派（M5.2）
    // =====================================================================

    override suspend fun executeAutonomousTask(request: AutonomousTaskRequest): TaskExecutionResult = taskMutex.withLock {
        executeAutonomousTaskLocked(request)
    }

    private suspend fun executeAutonomousTaskLocked(request: AutonomousTaskRequest): TaskExecutionResult {
        val loop = autonomousLoop
            ?: return TaskExecutionResult.Failure("单步动态闭环未装配（AutonomousActionLoop 缺失）", 0)
        isCancelled.set(false)
        // §7.1：动态闭环全程依赖语义树，无障碍不可用时直接坦白，不进入无意义的自愈循环
        if (!accessibilityReadyProvider()) {
            val reason = "无障碍服务当前不可用，无法进行界面代行"
            val narrative = "我的无障碍服务现在没在工作，碰不到屏幕。" +
                "你到「设置 - 无障碍」里帮我重新打开一下，我就能替你去办。"
            _state.value = TaskExecutionState.Failed(reason, 0, narrative)
            return TaskExecutionResult.Failure(reason, 0, narrative)
        }
        _state.value = TaskExecutionState.Planning(request.goal)
        emit(ActionVisualFeedback.BubbleState(EmbodiedState.THINKING, "我看看怎么弄"))

        return when (val result = loop.run(request)) {
            is AutonomousTaskResult.Success -> {
                _state.value = TaskExecutionState.Completed(result.message, result.steps)
                TaskExecutionResult.Success(result.message, result.steps)
            }
            is AutonomousTaskResult.Failure -> {
                _state.value = TaskExecutionState.Failed(result.reason, result.stepIndex, result.honestNarrative)
                TaskExecutionResult.Failure(result.reason, result.stepIndex, result.honestNarrative)
            }
            is AutonomousTaskResult.PolicyBlocked -> {
                // 漫游协商场景：这里给的是"征询"而不是"失败播报"
                val narrative = result.negotiationHint ?: result.honestReason
                _state.value = TaskExecutionState.Failed(result.reason, 0, narrative)
                TaskExecutionResult.Failure(result.reason, 0, narrative)
            }
            is AutonomousTaskResult.UserInterrupted -> {
                _state.value = TaskExecutionState.Failed(result.reason, result.stepIndex, result.reason)
                TaskExecutionResult.Failure(result.reason, result.stepIndex, result.reason)
            }
            AutonomousTaskResult.Cancelled -> {
                _state.value = TaskExecutionState.Idle
                TaskExecutionResult.Cancelled
            }
        }
    }

    // =====================================================================
    // 具身反馈（M5.1 方案A：接口先行，UI 解耦）
    // =====================================================================

    /** 注册额外反馈监听（悬浮窗接入用；订阅者异常由 Hub 隔离，绝不击穿执行链路） */
    override fun registerFeedbackListener(listener: ActionVisualFeedbackListener) {
        feedbackHub.register(listener)
    }

    private fun emit(event: ActionVisualFeedback) {
        feedbackHub.onFeedback(event)
    }

    private fun emitTouch(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long,
        kind: TouchKind
    ) {
        emit(
            ActionVisualFeedback.TouchTrace(
                startX = startX,
                startY = startY,
                endX = endX,
                endY = endY,
                durationMs = durationMs,
                kind = kind
            )
        )
    }

    private suspend fun executeUiElementAction(
        action: CoordinatorAction,
        query: com.lirica.android.automation.grounding.GroundingQuery,
        stepIndex: Int,
        totalSteps: Int
    ): AtomicExecutionOutcome {
        // 1. 抓取执行态高保真树
        var dump = treeExtractor.dumpTree(
            rootNode = rootNodeProvider(),
            screenWidth = screenWidthProvider(),
            screenHeight = screenHeightProvider(),
            packageName = currentPackageProvider()
        )

        if (dump is TreeDumpResult.Empty) {
            if (dump.reason == EmptyReason.PRIVACY_BLACKOUT) {
                val rule = "RULE_PRIVACY_BLACKOUT"
                val narrative = honestNarrator.narrate(
                    FailureContext(
                        scenario = FailureScenario.FINANCIAL_PROHIBITED,
                        rule = rule,
                        technicalReason = dump.message,
                        targetPackage = currentPackageProvider(),
                        actionType = if (action is CoordinatorAction.Click) "CLICK" else "INPUT"
                    )
                )
                _state.value = TaskExecutionState.Failed(
                    errorReason = "目标应用命中银行/支付安全熔断黑名单: ${dump.message}",
                    lastStepIndex = stepIndex,
                    honestNarrative = narrative
                )
                return AtomicExecutionOutcome.Blocked(
                    rule = rule,
                    reason = "目标应用命中银行/支付安全熔断黑名单: " + dump.message,
                    honestNarrative = narrative
                )
            }

            // 评估 watchdog：树抓取为空时以包名哈希作指纹（同包连续抓取失败同样构成"卡住"语义）
            val verdict = watchdog.inspectStep(
                fingerprint = (currentPackageProvider() ?: "").hashCode().toLong(),
                currentPackage = currentPackageProvider() ?: "",
                targetPackage = null,
                actionSummary = actionDescription(action)
            )
            if (verdict is WatchdogVerdict.Unrecoverable) {
                _state.value = TaskExecutionState.Failed(
                    errorReason = verdict.reason,
                    lastStepIndex = stepIndex,
                    honestNarrative = verdict.honestNarrative
                )
                return AtomicExecutionOutcome.Failed(verdict.reason, verdict.honestNarrative)
            }
            if (verdict is WatchdogVerdict.Recoverable) {
                _state.value = TaskExecutionState.ExecutingStep(stepIndex, totalSteps, "自愈中: ${verdict.anomaly}", isRecovering = true)
                watchdog.recordRecoveryAttempt(verdict.anomaly)
                applyRecoveryAction(verdict.suggestedAction, emptyList())
                dump = treeExtractor.dumpTree(rootNodeProvider(), screenWidthProvider(), screenHeightProvider(), currentPackageProvider())
            }
        }

        if (dump !is TreeDumpResult.Ok) {
            _state.value = TaskExecutionState.Failed("界面树抓取失败", stepIndex)
            return AtomicExecutionOutcome.Failed("界面树抓取失败")
        }

        // 2. 元素接地 (HybridGrounding)
        var locateResult = locateWithVisualFallback(query, dump.nodes)

        // 若仍未找到，检查是否有阻断弹窗并尝试自愈
        if (locateResult is GroundingResult.NotFound) {
            val verdict = watchdog.inspectStep(
                fingerprint = pageFingerprint(dump.nodes),
                currentPackage = currentPackageProvider() ?: "",
                targetPackage = null,
                actionSummary = actionDescription(action),
                pageTexts = dump.nodes.mapNotNull { it.text }
            )
            if (verdict is WatchdogVerdict.Unrecoverable) {
                _state.value = TaskExecutionState.Failed(
                    errorReason = verdict.reason,
                    lastStepIndex = stepIndex,
                    honestNarrative = verdict.honestNarrative
                )
                return AtomicExecutionOutcome.Failed(verdict.reason, verdict.honestNarrative)
            }
            if (verdict is WatchdogVerdict.Recoverable) {
                _state.value = TaskExecutionState.ExecutingStep(stepIndex, totalSteps, "自愈中: ${verdict.anomaly}", isRecovering = true)
                watchdog.recordRecoveryAttempt(verdict.anomaly)
                applyRecoveryAction(verdict.suggestedAction, dump.nodes)

                // 自愈后重新抓取与接地
                val retryDump = treeExtractor.dumpTree(rootNodeProvider(), screenWidthProvider(), screenHeightProvider(), currentPackageProvider())
                if (retryDump is TreeDumpResult.Ok) {
                    locateResult = locateWithVisualFallback(query, retryDump.nodes)
                }
            }
        }

        // 3. 智能滚屏搜寻（Scroll-and-Search，对齐 Mobile-Agent 核心机制）：
        // 当目标元素未在当前首屏可视区（如设置页底部的'系统和更新'或长列表中未展示的聊天），
        // 自动向上滑动半屏展开下游视野并二次接地，最多尝试 2 轮，杜绝首屏缺失直接报死。
        val canScrollSearch = locateResult is GroundingResult.NotFound &&
            (!query.text.isNullOrBlank() || !query.semanticDescription.isNullOrBlank() || !query.resourceId.isNullOrBlank())
        if (canScrollSearch) {
            val sw = screenWidthProvider().toFloat()
            val sh = screenHeightProvider().toFloat()
            if (sw > 0 && sh > 0) {
                var scrollRound = 0
                while (scrollRound < 2 && locateResult is GroundingResult.NotFound) {
                    scrollRound++
                    gestureExecutor.execute(
                        GestureSpec.Swipe(sw / 2f, sh * 0.72f, sw / 2f, sh * 0.32f, humanoidCurve = true)
                    )
                    kotlinx.coroutines.delay(600L)
                    val scrolledDump = treeExtractor.dumpTree(rootNodeProvider(), screenWidthProvider(), screenHeightProvider(), currentPackageProvider())
                    if (scrolledDump is TreeDumpResult.Ok) {
                        locateResult = locateWithVisualFallback(query, scrolledDump.nodes)
                    }
                }
            }
        }

        // 4. 智能歧义消解：当存在多个空间独立候选（如同名按钮或列表项）且未传锚点时，
        // 优选可交互且符合从上至下阅读顺序的首个控件，避免因歧义直接中断用户任务。
        if (locateResult is GroundingResult.Ambiguous) {
            val amb = locateResult as GroundingResult.Ambiguous
            val bestCandidate = amb.candidates
                .sortedWith(
                    compareByDescending<GroundingResult.Found> { it.refNode?.isClickable == true || it.refNode?.isEditable == true }
                        .thenBy { it.targetPoint.y }
                )
                .firstOrNull()
            if (bestCandidate != null) {
                locateResult = bestCandidate
            }
        }

        return when (locateResult) {
            is GroundingResult.NotFound -> {
                val elementText = query.text ?: query.semanticDescription ?: query.resourceId
                val narrative = honestNarrator.narrate(
                    FailureContext(
                        scenario = FailureScenario.GROUNDING_FAILED,
                        technicalReason = "未找到目标元素: $query",
                        targetPackage = currentPackageProvider(),
                        targetElement = elementText,
                        actionType = if (action is CoordinatorAction.Click) "CLICK" else "INPUT"
                    )
                )
                _state.value = TaskExecutionState.Failed(
                    errorReason = "未找到目标元素: $query",
                    lastStepIndex = stepIndex,
                    honestNarrative = narrative
                )
                AtomicExecutionOutcome.Failed("未找到目标元素: " + query, narrative)
            }
            is GroundingResult.Ambiguous -> {
                val narrative = honestNarrator.narrate(
                    FailureContext(
                        scenario = FailureScenario.GROUNDING_FAILED,
                        technicalReason = "目标元素存在歧义 (${locateResult.candidates.size} 个候选)",
                        targetPackage = currentPackageProvider(),
                        targetElement = query.text ?: query.resourceId
                    )
                )
                _state.value = TaskExecutionState.Failed(
                    errorReason = "目标元素存在歧义 (${locateResult.candidates.size} 个候选)",
                    lastStepIndex = stepIndex,
                    honestNarrative = narrative
                )
                AtomicExecutionOutcome.Failed("目标元素存在歧义 (" + locateResult.candidates.size + " 个候选)", narrative)
            }
            is GroundingResult.Found -> {
                val targetPoint = locateResult.targetPoint
                val refNode = locateResult.refNode
                val text = refNode?.text ?: refNode?.contentDescription ?: query.text ?: query.semanticDescription
                val resourceId = refNode?.resourceId ?: query.resourceId
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

                val decision = evaluateUiGate(proposed)
                var effectiveText = if (action is CoordinatorAction.Type) action.text else ""

                when (decision) {
                    is GatekeeperDecision.PhysicalBlocked -> {
                        val narrative = honestNarrator.narrate(
                            FailureContext(
                                rule = decision.rule,
                                technicalReason = decision.reason,
                                targetPackage = currentPackageProvider(),
                                actionType = proposed.actionType,
                                targetElement = proposed.targetText ?: proposed.targetResourceId
                            )
                        )
                        _state.value = TaskExecutionState.Failed(
                            errorReason = "零信任高危护栏物理熔断: ${decision.reason} (${decision.rule})",
                            lastStepIndex = stepIndex,
                            honestNarrative = narrative
                        )
                        return AtomicExecutionOutcome.Blocked(decision.rule, decision.reason, narrative)
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
                                approveToken(decision.token)
                                confirmation.complete(true)
                            },
                            onCancelled = {
                                confirmation.complete(false)
                            },
                            onConfirmedWithDraft = { edited ->
                                effectiveText = edited
                                approveToken(decision.token)
                                confirmation.complete(true)
                            }
                        )

                        try {
                            // CompletableDeferred 挂起等待二次确认（替代 while+delay 忙轮询；
                            // Deferred 的完成 happens-before 保证回调线程对 effectiveText 的写入对恢复点可见）
                            val approved = withTimeout(confirmationTimeoutMs) { confirmation.await() }
                            if (!approved || !gatekeeper.consumeApproval(decision.token)) {
                                _state.value = TaskExecutionState.Failed("用户取消或高危操作未获确认", stepIndex)
                                return AtomicExecutionOutcome.ConfirmationDeclined("用户取消或高危操作未获确认")
                            }
                        } catch (e: TimeoutCancellationException) {
                            _state.value = TaskExecutionState.Failed("用户确认超时 ($confirmationTimeoutMs ms)", stepIndex)
                            return AtomicExecutionOutcome.ConfirmationDeclined("用户确认超时")
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
                        emitTouch(targetPoint.x, targetPoint.y, targetPoint.x, targetPoint.y, 100L, TouchKind.TAP)
                        // M6.0 P1-2：触控抑制窗口（无障碍手势与 Shizuku input tap 两条路径都产生触控事件）
                        userInterruptionDetector?.onAutomationGestureDispatched(100L)
                        if (executionBackend != null && executionBackend.isAvailable()) {
                            // M6.0 P3-3：backend 返回值纳入 outcome——静默失败的 input tap
                            // 不能谎报成功（核销层虽有兜底，但 trace 与自愈策略都会被误导）
                            val backendOk = executionBackend.click(targetPoint.x, targetPoint.y)
                            if (!backendOk) {
                                val reason = "点击派发失败（执行通道返回失败）"
                                _state.value = TaskExecutionState.Failed(reason, stepIndex)
                                return AtomicExecutionOutcome.Failed(reason)
                            }
                        } else {
                            val clickGesture = GestureSpec.Click(
                                x = targetPoint.x,
                                y = targetPoint.y,
                                targetPackage = currentPackageProvider() ?: "",
                                targetText = text,
                                targetResourceId = resourceId,
                                isPasswordTarget = isPassword
                            )
                            val gestureResult = gestureExecutor.execute(clickGesture)
                            if (gestureResult is GestureResult.Cancelled && (gestureResult.reason.contains("受限") || gestureResult.reason.contains("blocked"))) {
                                val rule = "RULE_GESTURE_PHYSICAL_BLOCKED"
                                val narrative = honestNarrator.narrate(
                                    FailureContext(
                                        rule = rule,
                                        technicalReason = gestureResult.reason,
                                        targetPackage = currentPackageProvider(),
                                        actionType = "CLICK",
                                        targetElement = text ?: resourceId
                                    )
                                )
                                _state.value = TaskExecutionState.Failed(
                                    errorReason = "零信任高危护栏物理熔断: ${gestureResult.reason}",
                                    lastStepIndex = stepIndex,
                                    honestNarrative = narrative
                                )
                                return AtomicExecutionOutcome.Blocked(rule, gestureResult.reason, narrative)
                            }
                            // M6.0 P3-3：TimedOut / Error / 其余 Cancelled / 服务不可用
                            // 一律显式失败，不再谎报成功
                            when (gestureResult) {
                                is GestureResult.Completed -> Unit
                                is GestureResult.TimedOut -> {
                                    val reason = "点击手势派发超时 (${gestureResult.timeoutMs}ms)"
                                    _state.value = TaskExecutionState.Failed(reason, stepIndex)
                                    return AtomicExecutionOutcome.Failed(reason)
                                }
                                is GestureResult.Error -> {
                                    val reason = "点击手势派发异常: ${gestureResult.throwable.message}"
                                    _state.value = TaskExecutionState.Failed(reason, stepIndex)
                                    return AtomicExecutionOutcome.Failed(reason)
                                }
                                is GestureResult.Cancelled -> {
                                    val reason = "点击手势被系统取消: ${gestureResult.reason}"
                                    _state.value = TaskExecutionState.Failed(reason, stepIndex)
                                    return AtomicExecutionOutcome.Failed(reason)
                                }
                                GestureResult.ServiceUnavailable -> {
                                    val reason = "无障碍服务不可用，无法派发点击手势"
                                    _state.value = TaskExecutionState.Failed(reason, stepIndex)
                                    return AtomicExecutionOutcome.Failed(reason)
                                }
                            }
                        }
                        AtomicExecutionOutcome.Succeeded("点击 " + (text ?: resourceId ?: "元素"), bounds)
                    }
                    is CoordinatorAction.Type -> {
                        val textToInject = if (effectiveText.isNotBlank()) effectiveText else action.text
                        // 接地结果 refNode 是纯数据快照 (UiElementNode)，无法直接执行 ACTION_SET_TEXT / ACTION_PASTE；
                        // 必须按 bounds 从当前活动窗口恢复活节点后再注入——node=null 时两条注入路径都会静默失效。
                        val liveNode = findLiveNodeByBounds(bounds)
                        val inputSuccess = textInputExecutor.injectText(
                            node = liveNode,
                            targetBounds = bounds,
                            text = textToInject
                        )
                        if (!inputSuccess) {
                            val honest = textInputExecutor.lastHonestReason
                            val rule = "RULE_TEXT_INPUT_BLOCKED"
                            val narrative = if (honest != null) {
                                honestNarrator.narrate(
                                    FailureContext(
                                        rule = rule,
                                        technicalReason = honest,
                                        targetPackage = currentPackageProvider(),
                                        actionType = "INPUT",
                                        targetElement = text ?: resourceId
                                    )
                                )
                            } else null
                            val failMsg = if (honest != null) {
                                "零信任高危护栏物理熔断: $honest"
                            } else {
                                "文字注入失败"
                            }
                            _state.value = TaskExecutionState.Failed(
                                errorReason = failMsg,
                                lastStepIndex = stepIndex,
                                honestNarrative = narrative
                            )
                            return if (honest != null) {
                                AtomicExecutionOutcome.Blocked(rule, honest, narrative)
                            } else {
                                AtomicExecutionOutcome.Failed(failMsg, narrative)
                            }
                        }
                        AtomicExecutionOutcome.Succeeded("输入文本", bounds)
                    }
                    else -> AtomicExecutionOutcome.Succeeded(actionDescription(action), bounds)
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
        return editableFallback ?: runCatching {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
        }.getOrNull()
    }

    private suspend fun applyRecoveryAction(action: HealingAction, currentNodes: List<ExecutionNode>) {
        when (action) {
            is HealingAction.DismissPopup -> {
                // M6.0 B1 收口（体检报告 P2-2）：自愈点击必须过统一闸门。
                // 旧实现两处缺陷叠加：
                // 1. `contains` 宽泛匹配——页面上任意含"取消/关闭"字样的文本
                //    （如业务列表里的「取消订单」「关闭广告」）都可能被误点，
                //    触发真实业务副作用；
                // 2. GestureSpec 不带 targetText，闸门的文本级审查拿不到审查对象。
                // 新实现：整词匹配 + 必须可点击 + evaluateUiGate 全量裁定 +
                // GestureSpec 携带完整目标语义（GestureExecutorImpl 内部闸门同样生效）。
                // 找不到整词候选或闸门不放行时宁可不治——弹窗留给 watchdog
                // 升级到用户，绝不盲点。
                // 实现注记：未采用"改走 executeAtomic 重新接地"的方案——自愈发生在
                // locate 失败之后，重新 dump/locate 会与"先点掉弹窗再验证"的时序
                // 互相踩踏（测试 testExecuteTask_popupBlocking 建模的正是该时序）。
                // 闸门覆盖的本质要求由"协调器闸门 + 带语义的 GestureSpec"满足。
                val popupKeywords = SelfHealingWatchdogImpl.POPUP_KEYWORDS
                val candidate = currentNodes.firstOrNull { node: ExecutionNode ->
                    if (!node.isClickable) return@firstOrNull false
                    val text = node.text?.trim()
                    val desc = node.contentDescription?.trim()
                    popupKeywords.any { kw ->
                        text.equals(kw, ignoreCase = true) || desc.equals(kw, ignoreCase = true)
                    }
                }
                if (candidate == null) {
                    LiricaLog.w(
                        TAG,
                        "自愈未找到整词匹配的可点击弹窗按钮（候选 " + currentNodes.size + " 个节点），放弃盲点"
                    )
                    return
                }

                // 自愈点击与正常点击同权过闸：文本/资源 ID/包名全量送审
                val proposed = ProposedAction(
                    actionType = "CLICK",
                    targetText = candidate.text,
                    targetResourceId = candidate.resourceId,
                    isPasswordTarget = false,
                    currentPackage = currentPackageProvider() ?: ""
                )
                when (val decision = evaluateUiGate(proposed)) {
                    is GatekeeperDecision.PhysicalBlocked -> {
                        LiricaLog.w(TAG, "自愈点击被闸门熔断: ${decision.rule}，放弃并升级")
                        return
                    }
                    // 自愈链路内无法承载交互式确认卡片：需确认 = 不自动点击
                    is GatekeeperDecision.RequireUserConfirmation -> {
                        LiricaLog.w(TAG, "自愈点击命中需确认规则，放弃自动点击并升级")
                        return
                    }
                    is GatekeeperDecision.Allowed -> Unit
                }

                gestureExecutor.execute(
                    GestureSpec.Click(
                        x = candidate.bounds.centerX.toFloat(),
                        y = candidate.bounds.centerY.toFloat(),
                        targetPackage = currentPackageProvider() ?: "",
                        targetText = candidate.text,
                        targetResourceId = candidate.resourceId,
                        isPasswordTarget = false
                    )
                )
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

    private suspend fun locateWithVisualFallback(
        query: GroundingQuery,
        nodes: List<ExecutionNode>
    ): GroundingResult {
        val uiNodes = nodes.toUiElementNodes()
        var locateResult = groundingEngine.locate(
            query = query,
            tree = uiNodes,
            screenshot = null,
            screenWidth = screenWidthProvider(),
            screenHeight = screenHeightProvider()
        )

        // 若无障碍树未找到且具备截屏能力，触发 L1 视觉/OCR 接地。
        // §7.3：温度/电量熔断期间禁止截屏——视觉重通道是功耗与发热的主要来源，
        // 熔断只拦"看"，不拦轻量 API 动作（方案原文的电量梯度限流口径）。
        if (locateResult is GroundingResult.NotFound && screenCaptureManager != null && visionAllowedProvider()) {
            val screenshot = screenCaptureManager.captureScreen()?.bytes
            if (screenshot != null) {
                locateResult = groundingEngine.locate(
                    query = query,
                    tree = uiNodes,
                    screenshot = screenshot,
                    screenWidth = screenWidthProvider(),
                    screenHeight = screenHeightProvider()
                )
            }
        }
        return locateResult
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
        // 撤销全部待确认/已确认凭证：任务取消后凭证不得残留（防"确认了但没派发"的悬挂授权）
        safetyPolicy?.revokeAll()
        _state.value = TaskExecutionState.Idle
    }
}
