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
        val CRITICAL_PAYMENT_KEYWORDS = listOf(
            "立即支付", "确认支付", "输入支付密码", "微信支付", "支付宝支付", "云闪付",
            "确认付款", "立即付款", "pay now", "confirm payment", "transfer", "转账"
        )

        val CRITICAL_SYSTEM_KEYWORDS = listOf(
            "始终允许", "授予权限", "设备管理器", "device admin", "格式化", "恢复出厂设置", "注销账户"
        )

        val HIGH_RISK_CONFIRM_KEYWORDS = listOf(
            "提交订单", "立即购买", "发红包", "确认下单", "删除全部", "清空", "解除绑定", "修改密码", "重置密码"
        )

        val MEDIUM_RISK_KEYWORDS = listOf(
            "拨打", "发送", "设置", "呼叫", "dial", "call", "send"
        )
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
        val isBankApp = privacyBreaker.isSensitiveApp(action.currentPackage)

        // 1. Zero Tolerance: Password Input
        if (action.isPasswordTarget || resourceId.contains("password") || resourceId.contains("pwd") || resourceId.contains("pin")) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Direct automation of password input is strictly prohibited by security policy",
                rule = "RULE_ZERO_TOLERANCE_PASSWORD"
            )
        }

        // 2. Zero Tolerance: Direct Payment Confirmation
        val matchedPaymentKeyword = CRITICAL_PAYMENT_KEYWORDS.firstOrNull { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        if (matchedPaymentKeyword != null) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Automated execution on payment confirmation ('$matchedPaymentKeyword') is physically blocked",
                rule = "RULE_CRITICAL_PAYMENT_FUSE"
            )
        }

        // 3. Zero Tolerance: Bank app payment / transfer actions
        if (isBankApp && (text.contains("支付") || text.contains("转账") || text.contains("付款"))) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Automated payment action inside banking app (${action.currentPackage}) is physically blocked",
                rule = "RULE_BANK_APP_PAYMENT_FUSE"
            )
        }

        // 4. Zero Tolerance: System Security & Permission Escalation
        val matchedSystemKeyword = CRITICAL_SYSTEM_KEYWORDS.firstOrNull { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        if (matchedSystemKeyword != null) {
            return GatekeeperDecision.PhysicalBlocked(
                reason = "Automated security privilege escalation ('$matchedSystemKeyword') is physically blocked",
                rule = "RULE_SYSTEM_PRIVILEGE_FUSE"
            )
        }

        // 5. High Risk: Require Explicit User Confirmation
        val matchedHighRiskKeyword = HIGH_RISK_CONFIRM_KEYWORDS.firstOrNull { keyword ->
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

        // 6. Medium Risk: Audit Only
        val matchedMediumRisk = MEDIUM_RISK_KEYWORDS.firstOrNull { keyword ->
            text.contains(keyword, ignoreCase = true)
        }
        if (matchedMediumRisk != null) {
            return GatekeeperDecision.Allowed(RiskLevel.MEDIUM_RISK_AUDIT)
        }

        // 7. Safe
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
