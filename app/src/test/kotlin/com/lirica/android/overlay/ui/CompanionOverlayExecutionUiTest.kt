package com.lirica.android.overlay.ui

import com.lirica.android.automation.coordinator.ExecutionStepItem
import com.lirica.android.automation.coordinator.StepExecutionStatus
import com.lirica.android.automation.coordinator.TaskExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionOverlayExecutionUiTest {

    @Test
    fun testAwaitingUserConfirmation_withDraftContent_carriesDraftDetails() {
        var confirmedDraft = ""
        var wasCancelled = false

        val state = TaskExecutionState.AwaitingUserConfirmation(
            token = "token_abc_123",
            riskDetails = "准备发送微信消息，请核对内容",
            draftContent = "我二十分钟后到",
            draftTitle = "微信消息草稿",
            onConfirmed = {},
            onCancelled = { wasCancelled = true },
            onConfirmedWithDraft = { edited -> confirmedDraft = edited }
        )

        assertEquals("token_abc_123", state.token)
        assertEquals("微信消息草稿", state.draftTitle)
        assertEquals("我二十分钟后到", state.draftContent)
        assertEquals("准备发送微信消息，请核对内容", state.riskDetails)

        state.onConfirmedWithDraft?.invoke("我三十分钟后到")
        assertEquals("我三十分钟后到", confirmedDraft)

        state.onCancelled()
        assertTrue(wasCancelled)
    }

    @Test
    fun testExecutionStepItem_modelsAllStatuses() {
        val step1 = ExecutionStepItem(1, "启动高德地图", StepExecutionStatus.COMPLETED, 120L)
        val step2 = ExecutionStepItem(2, "点击搜索框", StepExecutionStatus.RUNNING)
        val step3 = ExecutionStepItem(3, "键入地址", StepExecutionStatus.PENDING)
        val step4 = ExecutionStepItem(4, "未知异常", StepExecutionStatus.FAILED)

        assertEquals(StepExecutionStatus.COMPLETED, step1.status)
        assertEquals(120L, step1.durationMs)
        assertEquals(StepExecutionStatus.RUNNING, step2.status)
        assertEquals(StepExecutionStatus.PENDING, step3.status)
        assertEquals(StepExecutionStatus.FAILED, step4.status)
    }

    @Test
    fun testExecutingStepState_drivesPulseAndCancellation() {
        val executingState = TaskExecutionState.ExecutingStep(
            stepIndex = 2,
            totalStepsEstimate = 5,
            actionDescription = "点击确认按钮",
            isRecovering = false
        )

        assertEquals(2, executingState.stepIndex)
        assertEquals(5, executingState.totalStepsEstimate)
        assertEquals("点击确认按钮", executingState.actionDescription)
        assertFalse(executingState.isRecovering)
    }
}
