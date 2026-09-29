package com.lirica.android.automation.healing

enum class AnomalyType {
    STUCK_LOOP,
    POPUP_BLOCKING,
    UNEXPECTED_EXIT,
    ACTION_NO_EFFECT
}

sealed interface HealingAction {
    data class DismissPopup(val targetKeyword: String? = null) : HealingAction
    data class PhysicalBacktrack(val steps: Int = 1) : HealingAction
    data class ReGround(val reason: String) : HealingAction
    data class EscalateToUser(val explanation: String) : HealingAction
}

data class ExecutionStep(
    val stepIndex: Int,
    val fingerprint: Long,
    val packageName: String,
    val actionSummary: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface WatchdogVerdict {
    data class NormalProgress(val stepIndex: Int, val fingerprint: Long) : WatchdogVerdict
    data class Recoverable(
        val anomaly: AnomalyType,
        val suggestedAction: HealingAction,
        val attemptCount: Int
    ) : WatchdogVerdict
    data class Unrecoverable(
        val reason: String,
        val honestNarrative: String? = null
    ) : WatchdogVerdict
}
