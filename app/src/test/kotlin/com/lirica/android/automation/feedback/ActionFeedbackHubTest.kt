package com.lirica.android.automation.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 具身反馈中枢回归（M5.1 方案A：执行引擎与 UI 解耦）。
 */
class ActionFeedbackHubTest {

    @Test
    fun testListenersReceiveEvents() {
        val received = mutableListOf<ActionVisualFeedback>()
        val hub = ActionFeedbackHub(listOf(ActionVisualFeedbackListener { received.add(it) }))
        hub.onFeedback(ActionVisualFeedback.BubbleState(EmbodiedState.ACTING, "点一下"))
        assertEquals(1, received.size)
        assertTrue(received[0] is ActionVisualFeedback.BubbleState)
    }

    @Test
    fun testThrowingListenerDoesNotBreakDispatch() {
        // UI 层崩溃绝不能击穿执行主链路：这是 Hub 存在的唯一理由
        val received = mutableListOf<ActionVisualFeedback>()
        val hub = ActionFeedbackHub(
            listOf(
                ActionVisualFeedbackListener { throw IllegalStateException("悬浮窗渲染炸了") },
                ActionVisualFeedbackListener { received.add(it) }
            )
        )
        hub.onFeedback(ActionVisualFeedback.BubbleState(EmbodiedState.IDLE))
        assertEquals("抛异常的订阅者不应阻断后续订阅者", 1, received.size)
    }

    @Test
    fun testRegisterAndUnregister() {
        val hub = ActionFeedbackHub()
        val listener = ActionVisualFeedbackListener { }
        hub.register(listener)
        assertEquals(1, hub.listenerCount)
        hub.unregister(listener)
        assertEquals(0, hub.listenerCount)
    }

    @Test
    fun testTouchTraceCarriesGestureGeometry() {
        val trace = ActionVisualFeedback.TouchTrace(
            startX = 10f,
            startY = 20f,
            endX = 30f,
            endY = 40f,
            durationMs = 120L,
            kind = TouchKind.SWIPE
        )
        assertEquals(TouchKind.SWIPE, trace.kind)
        assertEquals(120L, trace.durationMs)
    }

    @Test
    fun testNoOpListenerAcceptsEverything() {
        NoOpActionVisualFeedbackListener.onFeedback(
            ActionVisualFeedback.MacroReplayProgress(macroId = "m1", stepIndex = 1, totalSteps = 3)
        )
    }

    @Test
    fun testSharedHubReceivesEventsFromBothCoordinatorAndDirectDispatch() {
        val sharedHub = ActionFeedbackHub()
        val received = mutableListOf<ActionVisualFeedback>()
        sharedHub.register { received.add(it) }

        // 模拟 Coordinator 派发 TouchTrace
        sharedHub.onFeedback(
            ActionVisualFeedback.TouchTrace(0f, 0f, 100f, 100f, 50L, TouchKind.TAP)
        )

        // 模拟闭环派发 StepProgress
        sharedHub.onFeedback(
            ActionVisualFeedback.StepProgress(stepIndex = 1, totalSteps = 12, description = "点击搜索")
        )

        assertEquals(2, received.size)
        assertTrue(received[0] is ActionVisualFeedback.TouchTrace)
        assertTrue(received[1] is ActionVisualFeedback.StepProgress)
    }
}
