package com.xiaoxi.android.automation.gatekeeper

enum class RiskLevel {
    CRITICAL_BLOCK,
    HIGH_RISK_CONFIRM,
    MEDIUM_RISK_AUDIT,
    SAFE
}

data class ProposedAction(
    val actionType: String, // e.g. "CLICK", "INPUT", "INTENT", "GESTURE"
    val targetText: String? = null,
    val targetResourceId: String? = null,
    val isPasswordTarget: Boolean = false,
    val currentPackage: String = "",
    val extraInfo: Map<String, String> = emptyMap()
)

data class RiskDetails(
    val level: RiskLevel,
    val matchedRule: String,
    val description: String
)

sealed interface GatekeeperDecision {
    data class PhysicalBlocked(
        val reason: String,
        val rule: String
    ) : GatekeeperDecision

    data class RequireUserConfirmation(
        val token: String,
        val promptMessage: String,
        val details: RiskDetails
    ) : GatekeeperDecision

    data class Allowed(
        val riskLevel: RiskLevel
    ) : GatekeeperDecision
}
