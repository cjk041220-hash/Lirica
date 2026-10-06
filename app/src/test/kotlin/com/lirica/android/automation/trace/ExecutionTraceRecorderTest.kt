package com.lirica.android.automation.trace

import com.lirica.android.automation.observe.ObservationLevel
import com.lirica.android.automation.policy.ActionExecutionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 执行轨迹与三项核心指标回归（M5.0 §九 · P2-4 / R8）。
 */
class ExecutionTraceRecorderTest {

    private var now = 1_000L

    private fun recorder(sink: (ExecutionTrace) -> Unit = {}) =
        ExecutionTraceRecorder(capacity = 3, sink = sink, timeProvider = { now })

    private fun step(index: Int, durationMs: Long, outcome: TraceStep.VerificationOutcome) = TraceStep(
        index = index,
        description = "第 " + index + " 步",
        observationLevel = ObservationLevel.SEMANTIC_TREE,
        actionKind = "Click",
        startedAtMs = 0L,
        durationMs = durationMs,
        verification = outcome
    )

    @Test
    fun testTraceCapturesPlanMandatedFields() {
        val recorder = recorder()
        val builder = recorder.begin("打开设置", ActionExecutionMode.SHARED)
        builder.recordStep(step(1, 100L, TraceStep.VerificationOutcome.EFFECT_OBSERVED))
        builder.recordVlmCall()
        builder.recordSelfHeal()
        builder.recordInterruption()
        builder.recordTokenUsage(TokenUsage(promptTokens = 100, completionTokens = 20))
        now += 5_000L
        val trace = builder.finish(TraceFinalState.SUCCESS, now)

        assertEquals("打开设置", trace.goal)
        assertEquals(ActionExecutionMode.SHARED, trace.mode)
        assertEquals(5_000L, trace.durationMs)
        assertEquals(1, trace.stepCount)
        assertEquals(1, trace.vlmCallCount)
        assertEquals(1, trace.selfHealAttempts)
        assertEquals(1, trace.interruptionCount)
        assertEquals(120, trace.tokenUsage.totalTokens)
        assertEquals(TraceFinalState.SUCCESS, trace.finalState)
    }

    @Test
    fun testIneffectiveStepsAreCounted() {
        val builder = recorder().begin("目标", ActionExecutionMode.SHARED)
        builder.recordStep(step(1, 50L, TraceStep.VerificationOutcome.EFFECT_OBSERVED))
        builder.recordStep(step(2, 60L, TraceStep.VerificationOutcome.NO_VISIBLE_EFFECT))
        builder.recordStep(step(3, 70L, TraceStep.VerificationOutcome.NO_VISIBLE_EFFECT))
        val trace = builder.finish(TraceFinalState.FAILED)
        assertEquals(2, trace.ineffectiveStepCount)
    }

    @Test
    fun testMedianStepLatency() {
        val builder = recorder().begin("目标", ActionExecutionMode.SHARED)
        builder.recordStep(step(1, 10L, TraceStep.VerificationOutcome.EFFECT_OBSERVED))
        builder.recordStep(step(2, 30L, TraceStep.VerificationOutcome.EFFECT_OBSERVED))
        builder.recordStep(step(3, 20L, TraceStep.VerificationOutcome.EFFECT_OBSERVED))
        assertEquals(20L, builder.finish(TraceFinalState.SUCCESS).medianStepLatencyMs)
    }

    @Test
    fun testMetricsAggregateThreeCoreIndicators() {
        val recorder = recorder(sink = {})
        val success = recorder.begin("A", ActionExecutionMode.SHARED)
        success.recordStep(step(1, 100L, TraceStep.VerificationOutcome.EFFECT_OBSERVED))
        success.recordMacroHit(
            MacroHitRecord(
                macroId = "m1",
                intentSignature = "PLAY",
                startPageVerified = true,
                replaySteps = 2,
                downgradedToReAct = false
            )
        )
        recorder.complete(success.finish(TraceFinalState.SUCCESS))

        val failed = recorder.begin("B", ActionExecutionMode.SHARED)
        failed.recordStep(step(1, 300L, TraceStep.VerificationOutcome.NO_VISIBLE_EFFECT))
        recorder.complete(failed.finish(TraceFinalState.FAILED))

        val metrics = recorder.metrics()
        assertEquals(2, metrics.totalTasks)
        assertEquals(1, metrics.successTasks)
        assertEquals(1, metrics.macroHitTasks)
        assertEquals(0.5, metrics.successRate, 0.0001)
        assertEquals(0.5, metrics.macroHitRate, 0.0001)
        assertEquals("中位延迟取排序后的中位数", 300L, metrics.medianStepLatencyMs)
        assertEquals(1.0, metrics.averageStepsPerTask, 0.0001)
    }

    @Test
    fun testRingBufferKeepsOnlyLatestCapacity() {
        val recorder = recorder()
        repeat(5) { index ->
            val builder = recorder.begin("task-" + index, ActionExecutionMode.SHARED)
            recorder.complete(builder.finish(TraceFinalState.SUCCESS))
        }
        assertEquals(3, recorder.recentTraces().size)
        assertEquals("task-4", recorder.latestTrace()?.goal)
    }

    @Test
    fun testSinkIsInvokedAndExceptionsAreIsolated() {
        var sinkCalls = 0
        val recorder = ExecutionTraceRecorder(sink = {
            sinkCalls++
            throw IllegalStateException("导出器炸了")
        })
        val builder = recorder.begin("A", ActionExecutionMode.SHARED)
        recorder.complete(builder.finish(TraceFinalState.SUCCESS))
        assertEquals(1, sinkCalls)
        assertEquals("sink 抛异常不得影响轨迹入库", 1, recorder.recentTraces().size)
    }

    @Test
    fun testMetricsOnEmptyRecorder() {
        val metrics = recorder().metrics()
        assertEquals(0, metrics.totalTasks)
        assertEquals(0.0, metrics.successRate, 0.0001)
        assertEquals(0.0, metrics.macroHitRate, 0.0001)
        assertEquals(0L, metrics.medianStepLatencyMs)
    }

    @Test
    fun testClearResetsEverything() {
        val recorder = recorder()
        recorder.complete(recorder.begin("A", ActionExecutionMode.SHARED).finish(TraceFinalState.SUCCESS))
        recorder.clear()
        assertEquals(0, recorder.recentTraces().size)
        assertTrue(recorder.latestTrace() == null)
    }
}
