package com.lirica.android.automation.policy

import com.lirica.android.automation.gatekeeper.FinancialGatekeeper
import com.lirica.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.lirica.android.automation.gatekeeper.GatekeeperDecision
import com.lirica.android.automation.gatekeeper.ProhibitedZones
import com.lirica.android.automation.gatekeeper.ProposedAction
import com.lirica.android.automation.gatekeeper.RiskDetails
import com.lirica.android.automation.gatekeeper.RiskLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 统一安全通行裁决引擎（主方案5.0 §4.1 P0-1 收口 / 复审 R7 层级澄清）。
 *
 * 定位：**它不是 Layer 2 编排层的一部分，而是被 Layer 1 各通道入口与 Layer 2
 * 编排层共同调用的独立裁决服务**。之所以强调这一点——方案架构图把它画在
 * Layer 2，同时要求注入 Layer 1 的 dispatch() 入口；若按图把它收进 orchestration
 * 包，就会重现"某条通道漏调闸门"的历史缺口。任何新增通道都必须经过
 * [evaluate] 这一个入口。
 *
 * 职责边界（§1.1 表达与安全解耦）：
 * - 人格参数（支配度 D 锁、亲密等级）**不参与**本引擎的任何判定；
 * - 本引擎不生成动机、不做人设表达，只回答"这个动作此刻能不能通行"。
 */
interface SafetyPolicyEngine {

    /** 当前双态模式（共享 / 漫游只读） */
    val mode: StateFlow<ActionExecutionMode>

    fun setMode(mode: ActionExecutionMode)

    /**
     * 漫游模式下经用户对话协商后，临时提升**单次任务**作用域。
     *
     * @return 作用域令牌（任务结束时必须调用 [clearElevation] 归还）
     */
    fun elevateScopeForTask(taskId: String): String

    /** 当前处于作用域提升中的任务 ID；无提升时为 null */
    fun activeElevationTaskId(): String?

    /** 归还作用域提升（任务结束 / 用户反悔 / 模式切回共享） */
    fun clearElevation()

    /** 统一裁决入口 */
    fun evaluate(request: SafetyRequest, context: PolicyContext = currentContext()): SafetyDecision

    /** 当前上下文快照（模式 + 作用域提升态） */
    fun currentContext(): PolicyContext

    /** 标记待确认令牌已被用户批准（由二次确认卡片回调触发） */
    fun confirm(token: String): Boolean

    /**
     * 核销动作绑定凭证：只有"已批准 + 动作匹配 + 未过期 + 未使用"才返回 true，
     * 且核销后立即失效（一次性，防重放）。
     */
    fun consumeGrant(token: String, actionId: String): Boolean

    /** 会话级撤销：清空所有待确认与已确认凭证（用户切走/取消任务时调用） */
    fun revokeAll()
}

/**
 * 默认实现。
 *
 * 组合关系（§二 映射矩阵"安全收口统一"裁决）：
 * - 保留 [FinancialGatekeeper] 作为**内部模块**，继续承担金融/密码/通信代发的零容忍裁定；
 * - 新增 Intent 通道裁定与本引擎自有的动作绑定凭证；
 * - 新增漫游只读拦截 [ReadOnlyGuard]。
 */
class SafetyPolicyEngineImpl(
    private val gatekeeper: FinancialGatekeeper = FinancialGatekeeperImpl(),
    private val readOnlyGuard: ReadOnlyGuard = ReadOnlyGuard(),
    private val permissionProvider: AutomationPermissionProvider = AutomationPermissionProvider.ALL_DENIED,
    private val tokenTtlMs: Long = DEFAULT_TOKEN_TTL_MS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    initialMode: ActionExecutionMode = ActionExecutionMode.SHARED
) : SafetyPolicyEngine {

    private data class PendingGrant(
        val actionId: String,
        val createdAt: Long,
        @Volatile var approved: Boolean = false,
        @Volatile var consumed: Boolean = false
    )

    private data class ScopeElevation(
        val taskId: String,
        val token: String,
        val createdAt: Long
    )

    private val _mode = MutableStateFlow(initialMode)
    override val mode: StateFlow<ActionExecutionMode> = _mode.asStateFlow()

    private val grantStore = ConcurrentHashMap<String, PendingGrant>()

    @Volatile
    private var scopeElevation: ScopeElevation? = null

    override fun setMode(mode: ActionExecutionMode) {
        _mode.value = mode
        if (mode == ActionExecutionMode.SHARED) {
            // 切回共享模式即归还漫游授权：作用域提升绝不跨模式残留
            clearElevation()
        }
    }

    override fun elevateScopeForTask(taskId: String): String {
        val token = UUID.randomUUID().toString()
        scopeElevation = ScopeElevation(taskId = taskId, token = token, createdAt = timeProvider())
        return token
    }

    override fun activeElevationTaskId(): String? {
        val elevation = scopeElevation ?: return null
        if (timeProvider() - elevation.createdAt > SCOPE_ELEVATION_TTL_MS) {
            scopeElevation = null
            return null
        }
        return elevation.taskId
    }

    override fun clearElevation() {
        scopeElevation = null
    }

    override fun currentContext(): PolicyContext = PolicyContext(
        mode = _mode.value,
        hasScopeElevation = activeElevationTaskId() != null
    )

    override fun evaluate(request: SafetyRequest, context: PolicyContext): SafetyDecision {
        cleanupExpired()
        return when (request) {
            is SafetyRequest.UiInteraction -> evaluateUi(request, context)
            is SafetyRequest.IntentDispatch -> evaluateIntent(request.action, context)
            is SafetyRequest.ClipboardRead -> evaluateClipboard(request)
        }
    }

    // ---------------------------------------------------------------------
    // GUI 通道
    // ---------------------------------------------------------------------

    private fun evaluateUi(request: SafetyRequest.UiInteraction, context: PolicyContext): SafetyDecision {
        val actionId = when {
            request.actionType.contains("INPUT", ignoreCase = true) ||
                request.actionType.contains("PASTE", ignoreCase = true) -> SystemActionCatalog.ID_UI_INPUT_TEXT
            request.actionType.contains("LONG", ignoreCase = true) -> SystemActionCatalog.ID_UI_LONG_CLICK
            request.actionType.contains("SWIPE", ignoreCase = true) ||
                request.actionType.contains("SCROLL", ignoreCase = true) -> SystemActionCatalog.ID_UI_SWIPE
            // M6.0 P0-1：键事件通道必须在引擎层有目录归属，否则会掉进 else 分支被当成
            // 低危 CLICK 放行——这正是历史实现的"唯一没有门锁的侧门"
            request.actionType.contains("KEY", ignoreCase = true) -> SystemActionCatalog.ID_UI_KEY_EVENT
            else -> SystemActionCatalog.ID_UI_CLICK
        }
        val descriptor = SystemActionCatalog.of(actionId)
            ?: return unknownActionBlocked(actionId)

        // 第一层：金融/密码/通信代发零容忍（任何模式、任何作用域提升都不可解除）
        val proposed = ProposedAction(
            actionType = request.actionType,
            targetText = request.targetText,
            targetResourceId = request.targetResourceId,
            isPasswordTarget = request.isPasswordTarget,
            currentPackage = request.currentPackage
        )
        val gatekeeperDecision = gatekeeper.evaluate(proposed)
        if (gatekeeperDecision is GatekeeperDecision.PhysicalBlocked) {
            return SafetyDecision.Blocked(
                rule = gatekeeperDecision.rule,
                reason = gatekeeperDecision.reason,
                honestReason = gatekeeperDecision.honestReason,
                descriptor = descriptor
            )
        }

        // 第二层：漫游只读拦截（可经用户协商提升单次任务作用域）
        if (context.mode == ActionExecutionMode.ROAMING &&
            !context.hasScopeElevation &&
            SystemActionCatalog.leavesTraceInTargetApp(descriptor)
        ) {
            val verdict = readOnlyGuard.evaluate(
                actionId = actionId,
                targetText = request.targetText,
                contentDescription = null,
                resourceId = request.targetResourceId
            )
            if (verdict is ReadOnlyGuard.Verdict.RequiresNegotiation) {
                return SafetyDecision.Blocked(
                    rule = SafetyRuleIds.ROAMING_READ_ONLY_BLOCKED,
                    reason = "漫游只读模式下目标命中互动词「" + verdict.matchedKeyword + "」，需用户协商授权",
                    honestReason = "这个会让你在对方应用里留下痕迹，我想先问问你",
                    descriptor = descriptor
                )
            }
        }

        return SafetyDecision.fromGatekeeper(gatekeeperDecision, descriptor)
    }

    // ---------------------------------------------------------------------
    // Intent / 系统 API 通道
    // ---------------------------------------------------------------------

    private fun evaluateIntent(action: com.lirica.android.automation.intent.IntentAction, context: PolicyContext): SafetyDecision {
        val descriptor = SystemActionCatalog.describe(action)
            ?: return unknownActionBlocked(action::class.simpleName ?: "UNKNOWN_INTENT")

        if (descriptor.riskGrade == ActionRiskGrade.CRITICAL) {
            return SafetyDecision.Blocked(
                rule = SafetyRuleIds.INTENT_CRITICAL_BLOCKED,
                reason = "动作 " + descriptor.actionId + " 属零破坏不变量管辖范围，Intent 通道物理熔断",
                descriptor = descriptor
            )
        }

        // 漫游只读：非幂等外部通信需协商（可经作用域提升解除）
        if (context.mode == ActionExecutionMode.ROAMING &&
            !context.hasScopeElevation &&
            SystemActionCatalog.leavesTraceInTargetApp(descriptor)
        ) {
            return SafetyDecision.Blocked(
                rule = SafetyRuleIds.ROAMING_READ_ONLY_BLOCKED,
                reason = "漫游只读模式下禁止派发外部通信动作 " + descriptor.actionId,
                honestReason = "这会儿是漫游模式，我先不替你发出去",
                descriptor = descriptor
            )
        }

        // 权限前置：缺失即引导，绝不静默失败
        val permission = descriptor.permission
        if (permission != null && !permissionProvider.isGranted(permission)) {
            return SafetyDecision.Blocked(
                rule = if (permission.isSpecialSettingsGrant) {
                    SafetyRuleIds.WRITE_SETTINGS_GUIDED
                } else {
                    SafetyRuleIds.RUNTIME_PERMISSION_REQUIRED
                },
                reason = "动作 " + descriptor.actionId + " 缺少权限：" + (permission.manifestPermission ?: permission.name),
                honestReason = "还差一个系统授权，我带你过去开一下",
                descriptor = descriptor,
                permissionToRequest = permission
            )
        }

        if (descriptor.requiresUserConfirmation) {
            val token = UUID.randomUUID().toString()
            grantStore[token] = PendingGrant(actionId = descriptor.actionId, createdAt = timeProvider())
            return SafetyDecision.RequireConfirmation(
                token = token,
                descriptor = descriptor,
                promptMessage = buildConfirmationPrompt(action, descriptor),
                details = RiskDetails(
                    level = descriptor.riskGrade.toRiskLevel(),
                    matchedRule = descriptor.gateRule,
                    description = "动作 " + descriptor.actionId + " 属非幂等外部通信，正文需你过目后才可派发"
                )
            )
        }

        return SafetyDecision.Allowed(descriptor, descriptor.riskGrade.toRiskLevel())
    }

    // ---------------------------------------------------------------------
    // 剪贴板
    // ---------------------------------------------------------------------

    private fun evaluateClipboard(request: SafetyRequest.ClipboardRead): SafetyDecision {
        val descriptor = SystemActionCatalog.of(SystemActionCatalog.ID_READ_CLIPBOARD)
            ?: return unknownActionBlocked(SystemActionCatalog.ID_READ_CLIPBOARD)

        val legal = request.isUserInitiatedShare || (request.hasWindowFocus && request.isForeground)
        if (!legal) {
            return SafetyDecision.Blocked(
                rule = SafetyRuleIds.CLIPBOARD_FOREGROUND_ONLY,
                reason = "Android 10+ 禁止后台静默读取剪贴板：当前无窗口焦点且非用户主动分享",
                honestReason = "剪贴板得等你把界面给我、或者你主动分享给我，我才能看",
                descriptor = descriptor
            )
        }
        return SafetyDecision.Allowed(descriptor, RiskLevel.HIGH_RISK_CONFIRM)
    }

    // ---------------------------------------------------------------------
    // 凭证生命周期
    // ---------------------------------------------------------------------

    override fun confirm(token: String): Boolean {
        cleanupExpired()
        val entry = grantStore[token]
        if (entry != null) {
            entry.approved = true
            return true
        }
        // 兼容 GUI 通道：二次确认令牌由 FinancialGatekeeper 签发
        return gatekeeper.confirmAction(token)
    }

    override fun consumeGrant(token: String, actionId: String): Boolean {
        cleanupExpired()
        val entry = grantStore[token] ?: return false
        if (entry.actionId != actionId) return false
        if (!entry.approved || entry.consumed) return false
        entry.consumed = true
        grantStore.remove(token)
        return true
    }

    override fun revokeAll() {
        grantStore.clear()
    }

    private fun cleanupExpired() {
        val now = timeProvider()
        grantStore.entries.removeIf { now - it.value.createdAt > tokenTtlMs }
        val elevation = scopeElevation
        if (elevation != null && now - elevation.createdAt > SCOPE_ELEVATION_TTL_MS) {
            scopeElevation = null
        }
    }

    private fun unknownActionBlocked(actionId: String): SafetyDecision = SafetyDecision.Blocked(
        rule = SafetyRuleIds.UNKNOWN_ACTION_DENIED,
        reason = "动作 " + actionId + " 未登记在系统动作安全目录中，按 fail-closed 原则拒绝",
        honestReason = "这个动作我还没有登记过，稳妥起见先不做"
    )

    private fun buildConfirmationPrompt(
        action: com.lirica.android.automation.intent.IntentAction,
        descriptor: SafeActionDescriptor
    ): String = when (action) {
        is com.lirica.android.automation.intent.IntentAction.SendSms ->
            "要把这条短信草稿填进短信应用吗？内容：" + action.message
        is com.lirica.android.automation.intent.IntentAction.SendEmail ->
            "要把这封邮件草稿填进邮件应用吗？主题：" + (action.subject ?: "（无主题）")
        is com.lirica.android.automation.intent.IntentAction.ShareText ->
            "要把这段内容交给分享面板吗？内容：" + action.text
        else -> "动作 " + descriptor.actionId + " 需要你确认后我才能继续"
    }

    companion object {
        const val DEFAULT_TOKEN_TTL_MS: Long = 5 * 60 * 1000L

        /** 漫游作用域提升的有效期：单次任务，最长 30 分钟 */
        const val SCOPE_ELEVATION_TTL_MS: Long = 30 * 60 * 1000L
    }
}
