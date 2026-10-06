package com.lirica.android.automation.coordinator

import com.lirica.android.automation.grounding.GroundingQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行器延迟绑定桥回归。
 *
 * 断言重点：**装配期缺陷必须显式失败**，绝不能静默 no-op——
 * 静默 no-op 会把"没接线"伪装成"操作成功但屏幕没反应"。
 */
class AtomicActionExecutorBridgeTest {

    private val action = CoordinatorAction.Click(GroundingQuery(text = "搜索"), "点击搜索")

    @Test
    fun testUnboundBridgeFailsClosed() = runTest {
        val bridge = AtomicActionExecutorBridge()
        assertFalse(bridge.isBound)
        val outcome = bridge.executeAtomic(action, 1, 12)
        assertTrue(outcome is AtomicExecutionOutcome.Failed)
        assertTrue((outcome as AtomicExecutionOutcome.Failed).reason.contains("尚未绑定"))
    }

    @Test
    fun testDelegatesAfterBinding() = runTest {
        val bridge = AtomicActionExecutorBridge()
        var captured: CoordinatorAction? = null
        bridge.bind(object : AtomicActionExecutor {
            override suspend fun executeAtomic(
                action: CoordinatorAction,
                stepIndex: Int,
                totalSteps: Int
            ): AtomicExecutionOutcome {
                captured = action
                return AtomicExecutionOutcome.Succeeded("ok")
            }
        })
        assertTrue(bridge.isBound)
        val outcome = bridge.executeAtomic(action, 3, 12)
        assertTrue(outcome is AtomicExecutionOutcome.Succeeded)
        assertEquals(action, captured)
    }
}
