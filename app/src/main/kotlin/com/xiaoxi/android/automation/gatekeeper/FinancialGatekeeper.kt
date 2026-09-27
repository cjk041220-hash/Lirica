package com.xiaoxi.android.automation.gatekeeper

import com.xiaoxi.android.perception.PrivacyBreaker
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

interface FinancialGatekeeper {
    fun evaluate(action: ProposedAction): GatekeeperDecision
    fun confirmAction(token: String): Boolean
    fun isActionApproved(token: String): Boolean
    fun consumeApproval(token: String): Boolean
}

class FinancialGatekeeperImpl(
    private val tokenTtlMs: Long = 5 * 60 * 1000L, // 5 minutes
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val privacyBreaker: PrivacyBreaker = PrivacyBreaker()
) : FinancialGatekeeper {

    companion object {
        // 向后兼容引用 ProhibitedZones 中的只读常量与规则
        val COMMUNICATION_PACKAGES = ProhibitedZones.COMMUNICATION_PACKAGES
        val CRITICAL_PAYMENT_KEYWORDS = ProhibitedZones.CRITICAL_PAYMENT_KEYWORDS
        val CRITICAL_SYSTEM_KEYWORDS = ProhibitedZones.CRITICAL_SYSTEM_KEYWORDS
        val HIGH_RISK_CONFIRM_KEYWORDS = ProhibitedZones.HIGH_RISK_CONFIRM_KEYWORDS
        val MEDIUM_RISK_KEYWORDS = ProhibitedZones.MEDIUM_RISK_KEYWORDS

        fun isCommunicationApp(packageName: String): Boolean =
            ProhibitedZones.isCommunicationApp(packageName)
    }

    private data class TokenApproval(
        val action: ProposedAction,
        val createdAt: Long,
        var approved: Boolean = false
    )

    private val tokenStore = ConcurrentHashMap<String, TokenApproval>()

    override fun evaluate(action: ProposedAction): GatekeeperDecision {
        val text = action.targetText?.trim() ?: ""
        val resourceId = action.targetResourceId?.lowercase() ?: ""
        val isFinancial = ProhibitedZones.isFinancialApp(action.currentPackage) || privacyBreaker.isSensitiveApp(action.currentPackage)

        // 1. Zero Tolerance: Password Input (PRD D8 Tier 0)
        if (ProhibitedZones.isPasswordTarget(action.isPasswordTarget, resourceId, text)) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Direct automation of password input is strictly prohibited by security policy",
                rule = "RULE_ZERO_TOLERANCE_PASSWORD",
                honestReason = ProhibitedZones.DEFAULT_HONEST_REASON
            )
        }

        // 2. Zero Tolerance: Communication App Automated Text Input (PRD D8 Tier 0)
        // 通信软件（微信/QQ）文本输入类动作（代发/代回复）一律拦截，防止封号与社交灾难；点击/浏览类动作放行（PRD D7 第一层）
        if (ProhibitedZones.isCommunicationApp(action.currentPackage) && action.isTextInputAction) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Automated text input/messaging inside communication app (${action.currentPackage}) is physically blocked",
                rule = "RULE_COMMUNICATION_TEXT_INPUT_BLOCKED",
                honestReason = ProhibitedZones.DEFAULT_HONEST_REASON
            )
        }

        // 3. Zero Tolerance: Bank & Financial Apps Full Block (PRD D8 Tier 0: 银行类 App 全量)
        if (isFinancial) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Automated execution inside banking/financial app (${action.currentPackage}) is physically blocked",
                rule = "RULE_BANK_APP_PAYMENT_FUSE",
                honestReason = ProhibitedZones.DEFAULT_HONEST_REASON
            )
        }

        // 4. Zero Tolerance: Direct Payment Confirmation
        val matchedPaymentKeyword = ProhibitedZones.CRITICAL_PAYMENT_KEYWORDS.firstOrNull { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        if (matchedPaymentKeyword != null) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Automated execution on payment confirmation ('$matchedPaymentKeyword') is physically blocked",
                rule = "RULE_CRITICAL_PAYMENT_FUSE",
                honestReason = ProhibitedZones.DEFAULT_HONEST_REASON
            )
        }

        // 5. Zero Tolerance: System Security & Permission Escalation
        val matchedSystemKeyword = ProhibitedZones.CRITICAL_SYSTEM_KEYWORDS.firstOrNull { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        if (matchedSystemKeyword != null) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Automated security privilege escalation ('$matchedSystemKeyword') is physically blocked",
                rule = "RULE_SYSTEM_PRIVILEGE_FUSE",
                honestReason = ProhibitedZones.DEFAULT_HONEST_REASON
            )
        }

        // 6. High Risk: Require Explicit User Confirmation
        val matchedHighRiskKeyword = ProhibitedZones.HIGH_RISK_CONFIRM_KEYWORDS.firstOrNull { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        if (matchedHighRiskKeyword != null) {
            val token = UUID.randomUUID().toString()
            tokenStore[token] = TokenApproval(action, timeProvider())
            return GatekeeperDecision.RequireUserConfirmation(
                token = token,
                promptMessage = "检测到敏感操作「$matchedHighRiskKeyword」，需要您手动授权后伴生体方可代行",
                details = RiskDetails(
                    level = RiskLevel.HIGH_RISK_CONFIRM,
                    matchedRule = "RULE_HIGH_RISK_ORDER_OR_TRANSFER",
                    description = "Target text matched '$matchedHighRiskKeyword' in package ${action.currentPackage}"
                )
            )
        }

        // 7. Medium Risk: Audit Only
        val matchedMediumRisk = ProhibitedZones.MEDIUM_RISK_KEYWORDS.firstOrNull { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        if (matchedMediumRisk != null) {
            return GatekeeperDecision.Allowed(RiskLevel.MEDIUM_RISK_AUDIT)
        }

        // 8. Safe
        return GatekeeperDecision.Allowed(RiskLevel.SAFE)
    }

    override fun confirmAction(token: String): Boolean {
        cleanupExpired()
        val entry = tokenStore[token] ?: return false
        val now = timeProvider()
        if (now - entry.createdAt > tokenTtlMs) {
            tokenStore.remove(token)
            return false
        }
        entry.approved = true
        return true
    }

    override fun isActionApproved(token: String): Boolean {
        cleanupExpired()
        val entry = tokenStore[token] ?: return false
        val now = timeProvider()
        if (now - entry.createdAt > tokenTtlMs) {
            tokenStore.remove(token)
            return false
        }
        return entry.approved
    }

    override fun consumeApproval(token: String): Boolean {
        cleanupExpired()
        val entry = tokenStore[token] ?: return false
        val now = timeProvider()
        if (now - entry.createdAt > tokenTtlMs) {
            tokenStore.remove(token)
            return false
        }
        val wasApproved = entry.approved
        tokenStore.remove(token)
        return wasApproved
    }

    private fun cleanupExpired() {
        val now = timeProvider()
        tokenStore.entries.removeIf { now - it.value.createdAt > tokenTtlMs }
    }
}
