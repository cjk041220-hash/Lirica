package com.lirica.android.automation.coordinator

import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.lirica.android.automation.backend.ExecutionBackend
import com.lirica.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.lirica.android.automation.gesture.GestureExecutor
import com.lirica.android.automation.gesture.GestureResult
import com.lirica.android.automation.gesture.TextInputExecutor
import com.lirica.android.automation.grounding.ExecutionTreeExtractor
import com.lirica.android.automation.grounding.GroundingQuery
import com.lirica.android.automation.grounding.HybridGroundingEngine
import com.lirica.android.automation.healing.SelfHealingWatchdog
import com.lirica.android.automation.healing.WatchdogVerdict
import com.lirica.android.automation.intent.IntentActionDispatcher
import com.lirica.android.automation.policy.ActionExecutionMode
import com.lirica.android.automation.policy.SafetyPolicyEngineImpl
import com.lirica.android.perception.AccessibilityServiceHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 键事件通道闸门回归用例（M6.0 / 体检报告 P0-1 收口）。
 *
 * 体检结论：按键是唯一没有目标节点可供文本级审查的通道——ENTER 可激活焦点
 * 默认按钮（支付确认弹窗的默认按钮往往正是它），历史实现完全不过闸。
 * 本组用例按"规则 ID 即契约"原则逐条钉住四层裁定：
 * 1. 金融/敏感包名级熔断（RULE_BANK_APP_PAYMENT_FUSE）
 * 2. 通信应用内确认类按键 = 代发熔断（RULE_COMMUNICATION_TEXT_INPUT_BLOCKED）
 * 3. 活窗口支付关键词熔断（RULE_CRITICAL_PAYMENT_FUSE）
 * 4. 漫游只读熔断（RULE_ROAMING_READ_ONLY_BLOCKED）
 * 以及反向护栏：导航类按键（BACK）在通信应用内照常放行，不得过度拦截。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class KeyEventGateTest {

    private val intentDispatcher = mockk<IntentActionDispatcher>()
    private val treeExtractor = mockk<ExecutionTreeExtractor>()
    private val groundingEngine = mockk<HybridGroundingEngine>()
    private val gatekeeper = FinancialGatekeeperImpl()
    private val gestureExecutor = mockk<GestureExecutor>()
    private val textInputExecutor = mockk<TextInputExecutor>()
    private val watchdog = mockk<SelfHealingWatchdog>(relaxed = true)
    private val serviceHolder = mockk<AccessibilityServiceHolder>(relaxed = true)
    private val executionBackend = mockk<ExecutionBackend>()

    private var currentPackage: String = "com.example.app"
    private var windowNode: AccessibilityNodeInfo? = null
    private var roamingMode: Boolean = false

    @Before
    fun setUp() {
        coEvery { gestureExecutor.execute(any(), any()) } returns GestureResult.Completed(50L)
        coEvery { textInputExecutor.injectText(any(), any(), any()) } returns true
        every { textInputExecutor.lastHonestReason } returns null
        every { watchdog.inspectStep(any(), any(), any(), any(), any()) } returns WatchdogVerdict.NormalProgress(1, 0L)
        coEvery { executionBackend.isAvailable() } returns true
        coEvery { executionBackend.sendKeycode(any()) } returns true
    }

    private fun buildCoordinator(): HelixGuiCoordinatorImpl {
        return HelixGuiCoordinatorImpl(
            intentDispatcher = intentDispatcher,
            treeExtractor = treeExtractor,
            groundingEngine = groundingEngine,
            gatekeeper = gatekeeper,
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            watchdog = watchdog,
            serviceHolder = serviceHolder,
            rootNodeProvider = { windowNode ?: mockk(relaxed = true) },
            currentPackageProvider = { currentPackage },
            screenWidthProvider = { 1080 },
            screenHeightProvider = { 2400 },
            executionBackend = executionBackend,
            safetyPolicy = if (roamingMode) {
                SafetyPolicyEngineImpl(initialMode = ActionExecutionMode.ROAMING)
            } else {
                null
            }
        )
    }

    private fun pressEnterTask() = listOf<CoordinatorAction>(
        CoordinatorAction.PressKey(KeyEvent.KEYCODE_ENTER, "ENTER", "按回车键")
    )

    @Test
    fun testPressKeyEnter_blockedInFinancialApp() = runTest {
        currentPackage = "com.eg.android.AlipayGphone"
        val coordinator = buildCoordinator()

        val result = coordinator.executeTask("在支付宝里按回车", pressEnterTask())

        assertTrue(result is TaskExecutionResult.Failure)
        assertTrue(
            "金融包名级熔断规则必须出现在失败原因里，实际: ${(result as TaskExecutionResult.Failure).reason}",
            result.reason.contains("RULE_BANK_APP_PAYMENT_FUSE")
        )
        coVerify(exactly = 0) { executionBackend.sendKeycode(any()) }
    }

    @Test
    fun testPressKeyEnter_blockedInCommunicationApp() = runTest {
        currentPackage = "com.tencent.mm"
        val coordinator = buildCoordinator()

        val result = coordinator.executeTask("在微信里按回车发送", pressEnterTask())

        assertTrue(result is TaskExecutionResult.Failure)
        assertTrue(
            "通信应用内确认类按键必须按代发熔断，实际: ${(result as TaskExecutionResult.Failure).reason}",
            (result as TaskExecutionResult.Failure).reason.contains("RULE_COMMUNICATION_TEXT_INPUT_BLOCKED")
        )
        coVerify(exactly = 0) { executionBackend.sendKeycode(any()) }
    }

    @Test
    fun testPressKeyBack_allowedInCommunicationApp_navigationNotOverBlocked() = runTest {
        // 反向护栏：BACK 是导航键，不激活焦点元素——在通信应用内也必须放行，
        // 否则"按返回退出聊天"这类合法诉求会被过度拦截。
        currentPackage = "com.tencent.mm"
        val coordinator = buildCoordinator()

        val result = coordinator.executeTask(
            "在微信里按返回",
            listOf(CoordinatorAction.PressKey(KeyEvent.KEYCODE_BACK, "BACK", "按返回键"))
        )

        assertTrue(result is TaskExecutionResult.Success)
        coVerify(exactly = 1) { executionBackend.sendKeycode(KeyEvent.KEYCODE_BACK) }
    }

    @Test
    fun testPressKeyEnter_blockedByPaymentKeywordInWindow() = runTest {
        currentPackage = "com.example.app"
        val dangerNode = mockk<AccessibilityNodeInfo>()
        every { dangerNode.text } returns "确认支付"
        every { dangerNode.contentDescription } returns null
        every { dangerNode.viewIdResourceName } returns null
        every { dangerNode.isPassword } returns false
        every { dangerNode.childCount } returns 0
        windowNode = dangerNode
        val coordinator = buildCoordinator()

        val result = coordinator.executeTask("在普通应用里按回车", pressEnterTask())

        assertTrue(result is TaskExecutionResult.Failure)
        assertTrue(
            "窗口内支付关键词必须物理熔断，实际: ${(result as TaskExecutionResult.Failure).reason}",
            (result as TaskExecutionResult.Failure).reason.contains("RULE_CRITICAL_PAYMENT_FUSE")
        )
        coVerify(exactly = 0) { executionBackend.sendKeycode(any()) }
    }

    @Test
    fun testPressKeyEnter_allowedInNormalAppWithCleanWindow() = runTest {
        currentPackage = "com.example.app"
        val coordinator = buildCoordinator()

        val result = coordinator.executeTask("在搜索框按回车", pressEnterTask())

        assertTrue(result is TaskExecutionResult.Success)
        coVerify(exactly = 1) { executionBackend.sendKeycode(KeyEvent.KEYCODE_ENTER) }
    }

    @Test
    fun testPressKeyEnter_blockedInRoamingModeWithoutElevation() = runTest {
        currentPackage = "com.example.app"
        roamingMode = true
        val coordinator = buildCoordinator()

        val result = coordinator.executeTask("漫游模式下按回车", pressEnterTask())

        assertTrue(result is TaskExecutionResult.Failure)
        assertTrue(
            "漫游只读必须熔断确认类按键，实际: ${(result as TaskExecutionResult.Failure).reason}",
            (result as TaskExecutionResult.Failure).reason.contains("RULE_ROAMING_READ_ONLY_BLOCKED")
        )
        coVerify(exactly = 0) { executionBackend.sendKeycode(any()) }
    }

    @Test
    fun testConcurrentTasksSerialized_noCancelSignalClobbering() = runTest {
        // M6.0 B2：任务级互斥——并发发起的两个任务必须串行执行。
        // 历史缺陷：后到任务的 isCancelled.set(false) 会清掉前一个任务的取消请求，
        // _state 与 watchdog 步历史交错。互斥后后到任务在前者终止前不得开始派发。
        val order = mutableListOf<String>()
        coEvery { executionBackend.sendKeycode(any()) } coAnswers {
            order.add("start")
            kotlinx.coroutines.delay(100L)
            order.add("end")
            true
        }
        val coordinator = buildCoordinator()

        val t1 = launch {
            coordinator.executeTask("任务1", pressEnterTask())
        }
        val t2 = launch {
            coordinator.executeTask(
                "任务2",
                listOf(CoordinatorAction.PressKey(KeyEvent.KEYCODE_BACK, "BACK", "按返回"))
            )
        }
        t1.join()
        t2.join()

        assertEquals(
            "两个并发任务必须串行（start/end 不交错），实际: $order",
            listOf("start", "end", "start", "end"),
            order
        )
    }

    @Test
    fun testStateCarriesHonestNarrative_onKeyBlocked() = runTest {
        currentPackage = "com.eg.android.AlipayGphone"
        val coordinator = buildCoordinator()
        coordinator.executeTask("按回车", pressEnterTask())

        val state = coordinator.state.value
        assertTrue(state is TaskExecutionState.Failed)
        assertTrue(
            "熔断必须带人设化坦白叙述",
            !(state as TaskExecutionState.Failed).honestNarrative.isNullOrBlank()
        )
        assertEquals(1, state.lastStepIndex)
    }

    @Test
    fun testBackendSwipeFalse_reportsFailed_notFakeSuccess() = runTest {
        // M6.0 P3-3：backend 返回 false 必须显式失败——历史实现丢弃返回值，
        // 静默失败的 input swipe 被谎报为 Succeeded，trace 与自愈策略全被误导
        currentPackage = "com.example.app"
        coEvery { executionBackend.swipe(any(), any(), any(), any(), any()) } returns false
        val coordinator = buildCoordinator()

        val result = coordinator.executeTask(
            "向上滑动",
            listOf(CoordinatorAction.Swipe(SwipeDirection.UP, description = "向上滑动"))
        )

        assertTrue(result is TaskExecutionResult.Failure)
        assertTrue(
            "滑动失败必须可见，实际: ${(result as TaskExecutionResult.Failure).reason}",
            result.reason.contains("滑动派发失败")
        )
    }

    @Test
    fun testBackendClickFalse_reportsFailed_notFakeSuccess() = runTest {
        // M6.0 P3-3（点击路径）：接地成功但 backend click 返回 false 时必须显式失败
        currentPackage = "com.example.app"
        val dump = com.lirica.android.automation.grounding.TreeDumpResult.Ok(
            packageName = "com.example.app",
            activityName = "MainActivity",
            nodes = listOf(
                com.lirica.android.automation.grounding.ExecutionNode(
                    nodeId = "n0",
                    text = "确认",
                    contentDescription = null,
                    resourceId = null,
                    className = "android.widget.Button",
                    bounds = com.lirica.android.automation.grounding.ElementBounds(100, 200, 300, 300),
                    isClickable = true,
                    isEditable = false,
                    isCheckable = false,
                    isChecked = false,
                    isScrollable = false,
                    isEnabled = true,
                    isVisibleToUser = true,
                    isPassword = false,
                    priority = 0
                )
            ),
            totalDiscovered = 1,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 5L
        )
        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns dump
        coEvery { groundingEngine.locate(any(), any(), any(), any(), any()) } returns
            com.lirica.android.automation.grounding.GroundingResult.Found(
                targetPoint = com.lirica.android.automation.gesture.Point2D(200f, 250f),
                bounds = com.lirica.android.automation.grounding.NormalizedRect(90f, 80f, 280f, 130f),
                source = com.lirica.android.automation.grounding.GroundingSource.ACCESSIBILITY_EXACT,
                confidence = 0.95f,
                refNode = com.lirica.android.automation.grounding.UiElementNode(
                    text = "确认",
                    bounds = com.lirica.android.automation.grounding.ElementBounds(100, 200, 300, 300),
                    isClickable = true
                )
            )
        coEvery { executionBackend.click(any(), any()) } returns false
        val coordinator = buildCoordinator()

        val result = coordinator.executeTask(
            "点击确认",
            listOf(CoordinatorAction.Click(GroundingQuery(text = "确认"), description = "点击确认"))
        )

        assertTrue(result is TaskExecutionResult.Failure)
        assertTrue(
            "点击派发失败必须可见，实际: ${(result as TaskExecutionResult.Failure).reason}",
            result.reason.contains("点击派发失败")
        )
    }
}
