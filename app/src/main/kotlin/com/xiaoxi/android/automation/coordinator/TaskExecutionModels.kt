package com.xiaoxi.android.automation.coordinator

import com.xiaoxi.android.automation.grounding.GroundingQuery
import com.xiaoxi.android.automation.intent.IntentAction

sealed interface TaskExecutionState {
    data object Idle : TaskExecutionState
    data class Planning(val userGoal: String) : TaskExecutionState
    data class ExecutingStep(
        val stepIndex: Int,
        val totalStepsEstimate: Int,
        val actionDescription: String,
        val isRecovering: Boolean = false
    ) : TaskExecutionState
    data class AwaitingUserConfirmation(
        val token: String,
        val riskDetails: String,
        val onConfirmed: () -> Unit,
        val onCancelled: () -> Unit
    ) : TaskExecutionState
    data class Completed(val message: String, val stepsTaken: Int) : TaskExecutionState
    data class Failed(val errorReason: String, val lastStepIndex: Int) : TaskExecutionState
}

enum class SwipeDirection {
    UP, DOWN, LEFT, RIGHT
}

sealed interface CoordinatorAction {
    data class Intent(val action: IntentAction) : CoordinatorAction
    data class Click(
        val query: GroundingQuery,
        val description: String = "点击元素"
    ) : CoordinatorAction
    data class Type(
        val query: GroundingQuery,
        val text: String,
        val description: String = "输入文本"
    ) : CoordinatorAction
    data class Swipe(
        val direction: SwipeDirection,
        val humanoid: Boolean = true,
        val description: String = "滑动屏幕"
    ) : CoordinatorAction
    data class Backtrack(val description: String = "物理返回") : CoordinatorAction
    data class Finish(val summary: String = "代行任务已完成") : CoordinatorAction
}

sealed interface TaskExecutionResult {
    data class Success(val message: String, val stepsTaken: Int) : TaskExecutionResult
    data class Failure(val reason: String, val stepIndex: Int) : TaskExecutionResult
    data object Cancelled : TaskExecutionResult
}
