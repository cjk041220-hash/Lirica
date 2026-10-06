package com.lirica.android.automation.macro

import com.lirica.android.automation.verify.PageFingerprint
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应回放引擎回归（M5.3 §5.2 三条铁律）。
 *
 * 每条铁律对应一组断言：核验前置 / 逐步验证 / 版本淘汰（后者在查询层，见 DAO 测试）。
 */
class MacroReplayEngineTest {

    private val engine = MacroReplayEngine()

    private fun page(hash: Long) = PageFingerprint(
        hash = hash,
        packageName = "com.example.app",
        activityName = "MainActivity",
        nodeCount = 5,
        sampledAtMs = 1_000L
    )

    private fun macro(
        steps: List<MacroStep> = listOf(
            MacroStep(kind = MacroStepKind.CLICK, refId = "n0", normalizedX = 500, normalizedY = 100)
        ),
        startPageFingerprint: Long = 42L,
        slotKeys: List<String> = emptyList(),
        deprecated: Boolean = false
    ) = ActionMacro(
        macroId = "macro-1",
        intentSignature = "PLAY_MUSIC",
        appPackage = "com.example.app",
        targetAppVersionCode = 100L,
        startPageFingerprint = startPageFingerprint,
        steps = steps,
        explicitSlotKeys = slotKeys,
        isDeprecated = deprecated
    )

    @Test
    fun testRejectsEmptyStepList() = runTest {
        val outcome = engine.replay(macro(steps = emptyList()), emptyMap(), page(42L)) { _, _, _ ->
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.NotReplayable)
    }

    @Test
    fun testRejectsDeprecatedMacro() = runTest {
        val outcome = engine.replay(macro(deprecated = true), emptyMap(), page(42L)) { _, _, _ ->
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.NotReplayable)
    }

    @Test
    fun testRejectsMacroWithoutStartPageFingerprint() = runTest {
        // 起始页指纹为 0 = 录制时没采到 → 拒绝盲回放（§5.2 明文纪律）
        val outcome = engine.replay(macro(startPageFingerprint = 0L), emptyMap(), page(0L)) { _, _, _ ->
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.NotReplayable)
        assertTrue((outcome as MacroReplayOutcome.NotReplayable).reason.contains("盲回放"))
    }

    @Test
    fun testRejectsMacroLongerThanStepBudget() = runTest {
        // §1.2.3 有界性对宏回放同样成立：宏不是绕过 12 步硬顶的后门
        val longSteps = (0 until 20).map { MacroStep(kind = MacroStepKind.CLICK, normalizedX = it, normalizedY = it) }
        var executed = 0
        val outcome = engine.replay(
            macro = macro(steps = longSteps),
            slots = emptyMap(),
            currentPage = page(42L),
            maxSteps = 12
        ) { _, _, _ ->
            executed++
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.NotReplayable)
        assertTrue((outcome as MacroReplayOutcome.NotReplayable).reason.contains("步数上限"))
        assertEquals("超长宏一步都不该执行", 0, executed)
    }

    @Test
    fun testStartPageMismatchAbortsBeforeAnyStep() = runTest {
        var executed = 0
        val outcome = engine.replay(macro(), emptyMap(), page(999L)) { _, _, _ ->
            executed++
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.StartPageMismatch)
        assertEquals("起始页不匹配时一步都不许执行", 0, executed)
    }

    @Test
    fun testStartPageUnavailableIsAlsoRejected() = runTest {
        val outcome = engine.replay(macro(), emptyMap(), null) { _, _, _ ->
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.StartPageMismatch)
    }

    @Test
    fun testMissingSlotsFailFast() = runTest {
        var executed = 0
        val step = MacroStep(kind = MacroStepKind.TYPE, slotKey = "keyword", normalizedX = 500, normalizedY = 200)
        val outcome = engine.replay(macro(steps = listOf(step), slotKeys = listOf("keyword")), emptyMap(), page(42L)) { _, _, _ ->
            executed++
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.MissingSlots)
        assertEquals(listOf("keyword"), (outcome as MacroReplayOutcome.MissingSlots).missingKeys)
        assertEquals("槽位缺失必须前置失败，不能边跑边发现", 0, executed)
    }

    @Test
    fun testSlotValueIsResolvedAndInjected() = runTest {
        var injected: String? = null
        val step = MacroStep(kind = MacroStepKind.TYPE, slotKey = "keyword", normalizedX = 500, normalizedY = 200)
        val outcome = engine.replay(
            macro(steps = listOf(step), slotKeys = listOf("keyword")),
            mapOf("keyword" to "周杰伦"),
            page(42L)
        ) { _, resolvedText, _ ->
            injected = resolvedText
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.Success)
        assertEquals("周杰伦", injected)
    }

    @Test
    fun testStepFailureAbortsReplay() = runTest {
        var executed = 0
        val steps = listOf(
            MacroStep(kind = MacroStepKind.CLICK, normalizedX = 1, normalizedY = 1),
            MacroStep(kind = MacroStepKind.CLICK, normalizedX = 2, normalizedY = 2),
            MacroStep(kind = MacroStepKind.CLICK, normalizedX = 3, normalizedY = 3)
        )
        val outcome = engine.replay(macro(steps = steps), emptyMap(), page(42L)) { _, _, index ->
            executed++
            if (index == 1) MacroStepExecution(success = false, reason = "元素不存在") else MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.StepFailed)
        assertEquals(1, (outcome as MacroReplayOutcome.StepFailed).stepIndex)
        assertEquals("失败即中止，不得继续盲跑后续步骤", 2, executed)
    }

    @Test
    fun testUnverifiedStepAbortsReplay() = runTest {
        // 派发成功但核销未通过 = "看着在动、实际没生效"，必须中止并降级
        val steps = listOf(
            MacroStep(kind = MacroStepKind.CLICK, normalizedX = 1, normalizedY = 1),
            MacroStep(kind = MacroStepKind.CLICK, normalizedX = 2, normalizedY = 2)
        )
        var executed = 0
        val outcome = engine.replay(macro(steps = steps), emptyMap(), page(42L)) { _, _, _ ->
            executed++
            MacroStepExecution(success = true, verified = false)
        }
        assertTrue(outcome is MacroReplayOutcome.StepFailed)
        assertTrue((outcome as MacroReplayOutcome.StepFailed).reason.contains("核销"))
        assertEquals(1, executed)
    }

    @Test
    fun testPolicyBlockIsReportedSeparately() = runTest {
        val outcome = engine.replay(macro(), emptyMap(), page(42L)) { _, _, _ ->
            MacroStepExecution(success = false, reason = "漫游只读拦截", blockedRule = "RULE_ROAMING_READ_ONLY_BLOCKED")
        }
        assertTrue(outcome is MacroReplayOutcome.Blocked)
        assertEquals("RULE_ROAMING_READ_ONLY_BLOCKED", (outcome as MacroReplayOutcome.Blocked).rule)
    }

    @Test
    fun testSuccessfulReplayReportsStepCount() = runTest {
        val steps = (0 until 4).map { MacroStep(kind = MacroStepKind.CLICK, normalizedX = it, normalizedY = it) }
        val outcome = engine.replay(macro(steps = steps), emptyMap(), page(42L)) { _, _, _ ->
            MacroStepExecution(success = true)
        }
        assertTrue(outcome is MacroReplayOutcome.Success)
        assertEquals(4, (outcome as MacroReplayOutcome.Success).steps)
    }

    @Test
    fun testStrictStartPageVerifierRejectsInvalidFingerprint() {
        assertTrue(StartPageVerifier.STRICT.matches(42L, page(42L)))
        assertTrue(!StartPageVerifier.STRICT.matches(42L, page(43L)))
        assertTrue(!StartPageVerifier.STRICT.matches(42L, null))
        assertTrue("无效指纹（hash=0）不得视为匹配", !StartPageVerifier.STRICT.matches(0L, page(0L)))
    }
}
