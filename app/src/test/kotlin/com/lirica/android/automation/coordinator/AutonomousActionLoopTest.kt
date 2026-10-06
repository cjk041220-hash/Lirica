package com.lirica.android.automation.coordinator

import com.lirica.android.automation.grounding.GroundingQuery
import com.lirica.android.automation.healing.WatchdogVerdict
import com.lirica.android.automation.healing.SelfHealingWatchdog
import com.lirica.android.automation.macro.ActionMacro
import com.lirica.android.automation.macro.MacroStep
import com.lirica.android.automation.macro.MacroStepKind
import com.lirica.android.automation.macro.TrajectoryMemory
import com.lirica.android.automation.observe.NodeRole
import com.lirica.android.automation.observe.ObservationBuilder
import com.lirica.android.automation.observe.ObservationLevel
import com.lirica.android.automation.observe.ObservationNode
import com.lirica.android.automation.observe.ObservationResult
import com.lirica.android.automation.observe.ObservationSnapshot
import com.lirica.android.automation.policy.ActionExecutionMode
import com.lirica.android.automation.policy.SafetyPolicyEngineImpl
import com.lirica.android.automation.trace.ExecutionTraceRecorder
import com.lirica.android.automation.trace.TraceFinalState
import com.lirica.android.automation.verify.ActionExecutionBudget
import com.lirica.android.automation.verify.ActionVerification
import com.lirica.android.automation.verify.PageFingerprint
import com.lirica.android.automation.verify.StateFingerprintService
import com.lirica.android.automation.verify.UserInterruptionDetector
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单步动态 ReAct 闭环回归（M5.2 §5.1 · 四条硬边界）。
 *
 * 这是行动模块的核心测试类：它把"闭环能不能被信任"拆成可穷举的机械断言——
 * 预算熔断、自愈上限、抢占熔断、宏回放降级、安全拦截转协商。
 */
class AutonomousActionLoopTest {

    private var now = 10_000L

    private val observer = mockk<ObservationBuilder>()
    private val executor = mockk<AtomicActionExecutor>()
    private val fingerprintService = mockk<StateFingerprintService>(relaxed = false)
    private val watchdog = mockk<SelfHealingWatchdog>(relaxed = true)
    private val memory = mockk<TrajectoryMemory>(relaxed = true)

    private fun observation(packageName: String = "com.example.app"): ObservationSnapshot = ObservationSnapshot(
        level = ObservationLevel.SEMANTIC_TREE,
        packageName = packageName,
        activityName = "MainActivity",
        nodes = listOf(
            ObservationNode(
                refId = "n0",
                text = "搜索",
                contentDescription = null,
                resourceId = "com.example:id/search",
                className = "android.widget.Button",
                role = NodeRole.BUTTON,
                normalizedX = 500,
                normalizedY = 100,
                isClickable = true,
                isEditable = false,
                isEnabled = true,
                isChecked = false,
                isScrollable = false
            )
        ),
        truncatedCount = 0,
        promptText = "pkg=" + packageName,
        capturedAtMs = now
    )

    private fun loop(
        budget: ActionExecutionBudget = ActionExecutionBudget.DEFAULT,
        detector: UserInterruptionDetector = UserInterruptionDetector(timeProvider = { now }),
        interruptionAwaitMs: Long = 50L,
        policy: com.lirica.android.automation.policy.SafetyPolicyEngine? = null,
        planner: DynamicStepPlanner
    ) = AutonomousActionLoop(
        observer = observer,
        planner = planner,
        executor = executor,
        fingerprintService = fingerprintService,
        interruptionDetector = detector,
        watchdog = watchdog,
        traceRecorder = ExecutionTraceRecorder(),
        trajectoryMemory = memory,
        policyEngine = policy,
        budget = budget,
        currentPackageProvider = { "com.example.app" },
        appVersionCodeProvider = { 100L },
        timeProvider = { now },
        interruptionAwaitMs = interruptionAwaitMs
    )

    private fun stubObservation() {
        coEvery { observer.observe(any(), any(), any()) } returns ObservationResult.Success(observation())
        // 动作前的 "before" 采样：几乎所有执行路径都会取一次，统一在此打桩
        coEvery { fingerprintService.sampleOnce() } returns
            PageFingerprint(1L, "com.example.app", "MainActivity", 3, now)
    }

    private fun stubVerification(verification: ActionVerification) {
        coEvery { fingerprintService.verifyAfterAction(any(), any(), any()) } returns verification
    }

    private val clickAction = CoordinatorAction.Click(GroundingQuery(text = "搜索"), "点击 搜索")

    @Test
    fun testPlannerFinishYieldsSuccess() = runTest {
        stubObservation()
        val result = loop { PlannedStep.finish("已经打开了") }.run(AutonomousTaskRequest("打开设置"))
        assertTrue(result is AutonomousTaskResult.Success)
        assertEquals("已经打开了", (result as AutonomousTaskResult.Success).message)
    }

    @Test
    fun testSingleStepThenFinish() = runTest {
        stubObservation()
        stubVerification(ActionVerification.EffectObserved(ActionVerification.EffectReason.PAGE_FINGERPRINT_CHANGED, null, null))
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Succeeded("点击 搜索")

        var calls = 0
        val result = loop {
            calls++
            if (calls == 1) PlannedStep.act(clickAction, refId = "n0") else PlannedStep.finish("点到了")
        }.run(AutonomousTaskRequest("打开设置"))

        assertTrue(result is AutonomousTaskResult.Success)
        assertEquals(1, (result as AutonomousTaskResult.Success).steps)
    }

    @Test
    fun testStepBudgetFuseIsTriggered() = runTest {
        stubObservation()
        // 每步都"已生效"，但决策层永不收尾 → 必须被 12 步硬顶拦下
        stubVerification(ActionVerification.EffectObserved(ActionVerification.EffectReason.PAGE_FINGERPRINT_CHANGED, null, null))
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Succeeded("点击")

        val result = loop { PlannedStep.act(clickAction) }.run(AutonomousTaskRequest("无穷任务"))
        assertTrue(result is AutonomousTaskResult.Failure)
        val failure = result as AutonomousTaskResult.Failure
        assertEquals(TraceFinalState.BUDGET_EXHAUSTED, failure.finalState)
        assertTrue(failure.reason.contains("最大执行步数"))
    }

    @Test
    fun testWallClockFuseBeatsStepBudget() = runTest {
        stubObservation()
        stubVerification(ActionVerification.EffectObserved(ActionVerification.EffectReason.PAGE_FINGERPRINT_CHANGED, null, null))
        coEvery { executor.executeAtomic(any(), any(), any()) } answers {
            now += 30_000L
            AtomicExecutionOutcome.Succeeded("点击")
        }

        val result = loop { PlannedStep.act(clickAction) }.run(AutonomousTaskRequest("慢任务"))
        assertTrue(result is AutonomousTaskResult.Failure)
        assertTrue((result as AutonomousTaskResult.Failure).reason.contains("总耗时熔断"))
    }

    @Test
    fun testSelfHealBudgetExhaustion() = runTest {
        stubObservation()
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Failed("没点到")
        every { watchdog.inspectStep(any(), any(), any(), any(), any()) } returns WatchdogVerdict.NormalProgress(1, 1L)

        val result = loop { PlannedStep.act(clickAction) }.run(AutonomousTaskRequest("打不中的任务"))
        assertTrue(result is AutonomousTaskResult.Failure)
        assertTrue((result as AutonomousTaskResult.Failure).reason.contains("自愈"))
        // 自愈恰好 3 次，第 4 次失败即熔断
        coVerify(exactly = 4) { executor.executeAtomic(any(), any(), any()) }
    }

    @Test
    fun testUnrecoverableWatchdogStopsImmediately() = runTest {
        stubObservation()
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Failed("崩了")
        every { watchdog.inspectStep(any(), any(), any(), any(), any()) } returns
            WatchdogVerdict.Unrecoverable("目标应用已退出", "她好像退出了这个应用，我先停一下")

        val result = loop { PlannedStep.act(clickAction) }.run(AutonomousTaskRequest("必败任务"))
        assertTrue(result is AutonomousTaskResult.Failure)
        assertTrue((result as AutonomousTaskResult.Failure).reason.contains("已退出"))
        coVerify(exactly = 1) { executor.executeAtomic(any(), any(), any()) }
    }

    @Test
    fun testUserInterruptionBlocksBeforeActing() = runTest {
        stubObservation()
        val detector = UserInterruptionDetector(timeProvider = { now })
        detector.onPhysicalTouch(now)

        val result = loop(
            planner = { PlannedStep.act(clickAction) },
            detector = detector,
            interruptionAwaitMs = 50L
        ).run(AutonomousTaskRequest("被抢占的任务"))

        assertTrue(result is AutonomousTaskResult.UserInterrupted)
        coVerify(exactly = 0) { executor.executeAtomic(any(), any(), any()) }
    }

    @Test
    fun testUserResumeLetsLoopContinue() = runTest {
        stubObservation()
        stubVerification(ActionVerification.EffectObserved(ActionVerification.EffectReason.PAGE_FINGERPRINT_CHANGED, null, null))
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Succeeded("点击")
        val detector = UserInterruptionDetector(timeProvider = { now })
        detector.onPhysicalTouch(now)

        // 用户在悬浮胶囊上点"继续"：由外部协程模拟（闭环自身只在等待）
        val resumeJob = launch {
            kotlinx.coroutines.delay(20L)
            detector.requestResume()
        }
        var plannerCalls = 0
        val result = loop(
            detector = detector,
            interruptionAwaitMs = 5_000L,
            planner = {
                plannerCalls++
                if (plannerCalls == 1) PlannedStep.act(clickAction) else PlannedStep.finish("继续完成了")
            }
        ).run(AutonomousTaskRequest("续接的任务"))
        resumeJob.join()

        assertTrue(result is AutonomousTaskResult.Success)
    }

    @Test
    fun testPolicyBlockSurfacesAsNegotiationForRoaming() = runTest {
        stubObservation()
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Blocked(
            rule = AutonomousActionLoop.ROAMING_RULE,
            reason = "漫游只读模式下目标命中互动词「播放」",
            honestNarrative = "这个会让你留下播放记录"
        )

        val result = loop(
            planner = { PlannedStep.act(clickAction) },
            policy = SafetyPolicyEngineImpl(initialMode = ActionExecutionMode.ROAMING)
        ).run(AutonomousTaskRequest("看视频"))

        assertTrue(result is AutonomousTaskResult.PolicyBlocked)
        val blocked = result as AutonomousTaskResult.PolicyBlocked
        assertEquals(AutonomousActionLoop.ROAMING_RULE, blocked.rule)
        assertTrue("漫游拦截必须转成可协商的征询", blocked.negotiationHint != null)
    }

    @Test
    fun testHardPolicyBlockHasNoNegotiationHint() = runTest {
        stubObservation()
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Blocked(
            rule = "RULE_ZERO_TOLERANCE_PASSWORD",
            reason = "密码框物理熔断",
            honestNarrative = "这个我不能碰"
        )
        val result = loop { PlannedStep.act(clickAction) }.run(AutonomousTaskRequest("输入密码"))
        assertTrue(result is AutonomousTaskResult.PolicyBlocked)
        assertEquals(null, (result as AutonomousTaskResult.PolicyBlocked).negotiationHint)
    }

    @Test
    fun testConfirmationDeclinedIsPolicyBlockedState() = runTest {
        stubObservation()
        coEvery { executor.executeAtomic(any(), any(), any()) } returns
            AtomicExecutionOutcome.ConfirmationDeclined("用户取消")
        val result = loop { PlannedStep.act(clickAction) }.run(AutonomousTaskRequest("发短信"))
        assertTrue(result is AutonomousTaskResult.Failure)
        assertEquals(TraceFinalState.POLICY_BLOCKED, (result as AutonomousTaskResult.Failure).finalState)
    }

    @Test
    fun testObservationUnavailableTriggersBoundedSelfHeal() = runTest {
        coEvery { observer.observe(any(), any(), any()) } returns ObservationResult.Unavailable(
            attemptedLevels = listOf(ObservationLevel.SEMANTIC_TREE),
            reason = "无障碍树为空"
        )
        every { watchdog.inspectStep(any(), any(), any(), any(), any()) } returns WatchdogVerdict.NormalProgress(1, 1L)

        val result = loop { PlannedStep.act(clickAction) }.run(AutonomousTaskRequest("看不见的任务"))
        assertTrue(result is AutonomousTaskResult.Failure)
        assertTrue((result as AutonomousTaskResult.Failure).reason.contains("自愈"))
    }

    @Test
    fun testPlannerFailureIsBoundedBySelfHealBudget() = runTest {
        stubObservation()
        every { watchdog.inspectStep(any(), any(), any(), any(), any()) } returns WatchdogVerdict.NormalProgress(1, 1L)
        val result = loop { null }.run(AutonomousTaskRequest("决策不了的任务"))
        assertTrue(result is AutonomousTaskResult.Failure)
    }

    @Test
    fun testMacroStartPageMismatchDowngradesToDynamicLoop() = runTest {
        stubObservation()
        stubVerification(ActionVerification.EffectObserved(ActionVerification.EffectReason.PAGE_FINGERPRINT_CHANGED, null, null))
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Succeeded("点击")

        coEvery { memory.findUsable(any(), any(), any()) } returns ActionMacro(
            macroId = "macro-1",
            intentSignature = "OPEN_SETTINGS",
            appPackage = "com.example.app",
            targetAppVersionCode = 100L,
            startPageFingerprint = 42L,
            steps = listOf(MacroStep(kind = MacroStepKind.CLICK, normalizedX = 1, normalizedY = 1)),
            explicitSlotKeys = emptyList()
        )
        // 当前页面指纹是 1，与录制起始页 42 不符 → 起始页核验失败 → 降级动态闭环
        val result = loop { PlannedStep.finish("动态跑完了") }
            .run(AutonomousTaskRequest(goal = "打开设置", intentSignature = "OPEN_SETTINGS"))

        assertTrue(result is AutonomousTaskResult.Success)
        coVerify(exactly = 1) { memory.markReplayFailure("macro-1") }
    }

    @Test
    fun testMacroRecordingOnlyWhenIntentSignatureDeclared() = runTest {
        stubObservation()
        stubVerification(ActionVerification.EffectObserved(ActionVerification.EffectReason.PAGE_FINGERPRINT_CHANGED, null, null))
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Succeeded("点击")
        coEvery { memory.record(any(), any()) } returns null

        var calls = 0
        val planner = DynamicStepPlanner {
            calls++
            if (calls == 1) PlannedStep.act(clickAction, refId = "n0") else PlannedStep.finish("完成")
        }

        // 未声明意图签名 → 不录宏
        loop(planner = planner).run(AutonomousTaskRequest(goal = "随手点一下"))
        coVerify(exactly = 0) { memory.record(any(), any()) }

        // 声明意图签名 → 录宏
        calls = 0
        loop(planner = planner).run(AutonomousTaskRequest(goal = "打开设置", intentSignature = "OPEN_SETTINGS"))
        coVerify(exactly = 1) { memory.record(any(), any()) }
    }

    @Test
    fun testTraceIsRecordedEvenOnFailure() = runTest {
        stubObservation()
        coEvery { executor.executeAtomic(any(), any(), any()) } returns AtomicExecutionOutcome.Failed("失败")
        every { watchdog.inspectStep(any(), any(), any(), any(), any()) } returns WatchdogVerdict.NormalProgress(1, 1L)

        val recorder = ExecutionTraceRecorder()
        val loopWithRecorder = AutonomousActionLoop(
            observer = observer,
            planner = { PlannedStep.act(clickAction) },
            executor = executor,
            fingerprintService = fingerprintService,
            interruptionDetector = UserInterruptionDetector(timeProvider = { now }),
            watchdog = watchdog,
            traceRecorder = recorder,
            budget = ActionExecutionBudget.DEFAULT,
            timeProvider = { now }
        )
        loopWithRecorder.run(AutonomousTaskRequest("必败任务"))
        assertEquals(1, recorder.recentTraces().size)
        assertEquals(TraceFinalState.FAILED, recorder.latestTrace()!!.finalState)
        assertTrue(recorder.latestTrace()!!.selfHealAttempts >= 1)
    }

    @Test
    fun testCancellationHookStopsDispatchImmediately() = runTest {
        // 用户喊停后闭环必须立刻停止派发：只改状态显示、屏幕还在自己动，
        // 是比失败更严重的问题
        stubObservation()
        val loopWithCancel = AutonomousActionLoop(
            observer = observer,
            planner = { PlannedStep.act(clickAction) },
            executor = executor,
            fingerprintService = fingerprintService,
            interruptionDetector = UserInterruptionDetector(timeProvider = { now }),
            watchdog = watchdog,
            traceRecorder = ExecutionTraceRecorder(),
            budget = ActionExecutionBudget.DEFAULT,
            timeProvider = { now },
            cancellationCheck = { true }
        )
        val result = loopWithCancel.run(AutonomousTaskRequest("被取消的任务"))
        assertEquals(AutonomousTaskResult.Cancelled, result)
        coVerify(exactly = 0) { executor.executeAtomic(any(), any(), any()) }
    }

    @Test
    fun testResourceGuardBlocksEverythingOnThermalCritical() = runTest {
        stubObservation()
        val loopWithGuard = AutonomousActionLoop(
            observer = observer,
            planner = { PlannedStep.act(clickAction) },
            executor = executor,
            fingerprintService = fingerprintService,
            interruptionDetector = UserInterruptionDetector(timeProvider = { now }),
            watchdog = watchdog,
            traceRecorder = ExecutionTraceRecorder(),
            resourceGuard = com.lirica.android.automation.policy.PhysicalResourceGuard(
                stateProvider = {
                    com.lirica.android.perception.model.HardwareEnvironmentState(
                        batteryTemperatureCelsius = 50f
                    )
                }
            ),
            timeProvider = { now }
        )
        val result = loopWithGuard.run(AutonomousTaskRequest("烫手任务"))
        assertTrue(result is AutonomousTaskResult.PolicyBlocked)
        coVerify(exactly = 0) { executor.executeAtomic(any(), any(), any()) }
    }
}
