package com.lirica.android.automation.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务级硬性预算回归（M5.0 §1.2.3 有界自愈公理）。
 *
 * 这里的每个数字都是方案条款，改动即违约——因此逐个断言"恰好用尽"与"超限即拒"。
 */
class ActionExecutionBudgetTest {

    @Test
    fun testDefaultBudgetMatchesPlanClauses() {
        val budget = ActionExecutionBudget.DEFAULT
        assertEquals("方案 §1.2.3：单任务 12 步", 12, budget.maxSteps)
        assertEquals("方案 §1.2.3：自愈累计 <= 3 次", 3, budget.maxSelfHealAttempts)
        assertEquals("方案 §5.1：VLM 硬顶 5 次", 5, budget.maxVlmCalls)
        assertEquals("方案 §1.2.3：总耗时 120 秒熔断", 120_000L, budget.maxWallClockMs)
        assertEquals("方案 §5.1：语义裁剪树 <= 30 项", 30, budget.maxObservationNodes)
        assertEquals("方案 §5.1：Prompt <= 8KB", 8_192, budget.maxObservationChars)
        assertEquals("方案 §5.2：连续 2 次失败弃用", 2, budget.maxConsecutiveMacroFailures)
    }

    @Test
    fun testStepBudgetIsExhaustedExactlyAtLimit() {
        val ledger = BudgetLedger(ActionExecutionBudget.DEFAULT) { 0L }
        ledger.start(0L)
        repeat(12) { index ->
            assertEquals("第 " + (index + 1) + " 步应当被允许", BudgetCheck.Ok, ledger.consumeStep())
        }
        val exhausted = ledger.consumeStep()
        assertTrue(exhausted is BudgetCheck.Exhausted)
        assertEquals(BudgetDimension.STEPS, (exhausted as BudgetCheck.Exhausted).dimension)
        assertEquals("超限时不得递增计数", 12, ledger.steps)
    }

    @Test
    fun testSelfHealBudget() {
        val ledger = BudgetLedger(ActionExecutionBudget.DEFAULT) { 0L }
        ledger.start(0L)
        repeat(3) { assertEquals(BudgetCheck.Ok, ledger.consumeSelfHeal()) }
        val exhausted = ledger.consumeSelfHeal()
        assertTrue(exhausted is BudgetCheck.Exhausted)
        assertEquals(BudgetDimension.SELF_HEAL, (exhausted as BudgetCheck.Exhausted).dimension)
    }

    @Test
    fun testVlmBudget() {
        val ledger = BudgetLedger(ActionExecutionBudget.DEFAULT) { 0L }
        ledger.start(0L)
        repeat(5) { assertEquals(BudgetCheck.Ok, ledger.consumeVlmCall()) }
        assertTrue(ledger.consumeVlmCall() is BudgetCheck.Exhausted)
        assertEquals(5, ledger.vlmCalls)
    }

    @Test
    fun testWallClockFuse() {
        var now = 1_000L
        val ledger = BudgetLedger(ActionExecutionBudget.DEFAULT) { now }
        ledger.start(1_000L)
        now = 1_000L + 119_999L
        assertEquals(BudgetCheck.Ok, ledger.checkDeadline())
        now = 1_000L + 120_000L
        val exhausted = ledger.checkDeadline()
        assertTrue(exhausted is BudgetCheck.Exhausted)
        assertEquals(BudgetDimension.WALL_CLOCK, (exhausted as BudgetCheck.Exhausted).dimension)
        assertEquals(0L, ledger.remainingMs())
    }

    @Test
    fun testMacroFailureThreshold() {
        val ledger = BudgetLedger(ActionExecutionBudget.DEFAULT) { 0L }
        ledger.start(0L)
        assertEquals(BudgetCheck.Ok, ledger.recordMacroFailure())
        val exhausted = ledger.recordMacroFailure()
        assertTrue(exhausted is BudgetCheck.Exhausted)
        assertEquals(BudgetDimension.MACRO_FAILURES, (exhausted as BudgetCheck.Exhausted).dimension)
        ledger.resetMacroFailures()
        assertEquals(BudgetCheck.Ok, ledger.recordMacroFailure())
    }

    @Test
    fun testStartResetsAllCounters() {
        val ledger = BudgetLedger(ActionExecutionBudget.DEFAULT) { 0L }
        ledger.start(0L)
        ledger.consumeStep()
        ledger.consumeSelfHeal()
        ledger.consumeVlmCall()
        ledger.recordMacroFailure()
        ledger.start(0L)
        assertEquals(0, ledger.steps)
        assertEquals(0, ledger.selfHealAttempts)
        assertEquals(0, ledger.vlmCalls)
        assertEquals(0, ledger.macroFailureCount)
    }

    @Test
    fun testInvalidBudgetRejected() {
        assertTrue(runCatching { ActionExecutionBudget(maxSteps = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ActionExecutionBudget(maxWallClockMs = 0) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { ActionExecutionBudget(maxSelfHealAttempts = -1) }.exceptionOrNull() is IllegalArgumentException)
    }
}
