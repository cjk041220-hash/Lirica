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
) {
    val isTextInputAction: Boolean
        get() = actionType.equals("INPUT", ignoreCase = true) ||
                actionType.equals("TEXT_INPUT", ignoreCase = true) ||
                actionType.equals("ACTION_SET_TEXT", ignoreCase = true) ||
                actionType.equals("ACTION_PASTE", ignoreCase = true) ||
                actionType.contains("INPUT", ignoreCase = true) ||
                actionType.contains("PASTE", ignoreCase = true) ||
                extraInfo["isTextInput"]?.toBoolean() == true
}

data class RiskDetails(
    val level: RiskLevel,
    val matchedRule: String,
    val description: String
)

sealed interface GatekeeperDecision {
    data class PhysicalBlocked(
        val reason: String,
        val rule: String,
        val honestReason: String = DEFAULT_HONEST_REASON
    ) : GatekeeperDecision {
        companion object {
            const val DEFAULT_HONEST_REASON = "该操作属于受限范围，已停止"
        }
    }

    data class RequireUserConfirmation(
        val token: String,
        val promptMessage: String,
        val details: RiskDetails
    ) : GatekeeperDecision

    data class Allowed(
        val riskLevel: RiskLevel
    ) : GatekeeperDecision
}
