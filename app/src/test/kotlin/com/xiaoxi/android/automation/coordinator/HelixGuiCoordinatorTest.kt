package com.xiaoxi.android.automation.coordinator

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import com.xiaoxi.android.automation.backend.ExecutionBackend
import com.xiaoxi.android.automation.capture.FrameCaptureResult
import com.xiaoxi.android.automation.capture.ScreenCaptureManager
import com.xiaoxi.android.automation.gatekeeper.FinancialGatekeeper
import com.xiaoxi.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.xiaoxi.android.automation.gesture.GestureExecutor
import com.xiaoxi.android.automation.gesture.GestureResult
import com.xiaoxi.android.automation.gesture.Point2D
import com.xiaoxi.android.automation.gesture.TextInputExecutor
import com.xiaoxi.android.automation.grounding.ElementBounds
import com.xiaoxi.android.automation.grounding.EmptyReason
import com.xiaoxi.android.automation.grounding.ExecutionNode
import com.xiaoxi.android.automation.grounding.ExecutionTreeExtractor
import com.xiaoxi.android.automation.grounding.GroundingQuery
import com.xiaoxi.android.automation.grounding.GroundingResult
import com.xiaoxi.android.automation.grounding.GroundingSource
import com.xiaoxi.android.automation.grounding.HybridGroundingEngine
import com.xiaoxi.android.automation.grounding.NormalizedRect
import com.xiaoxi.android.automation.grounding.TreeDumpResult
import com.xiaoxi.android.automation.grounding.UiElementNode
import com.xiaoxi.android.automation.healing.AnomalyType
import com.xiaoxi.android.automation.healing.HealingAction
import com.xiaoxi.android.automation.healing.SelfHealingWatchdog
import com.xiaoxi.android.automation.healing.WatchdogVerdict
import com.xiaoxi.android.automation.intent.DispatchResult
import com.xiaoxi.android.automation.intent.IntentAction
import com.xiaoxi.android.automation.intent.IntentActionDispatcher
import com.xiaoxi.android.automation.intent.SettingType
import com.xiaoxi.android.perception.AccessibilityServiceHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class HelixGuiCoordinatorTest {

    private val intentDispatcher = mockk<IntentActionDispatcher>()
    private val treeExtractor = mockk<ExecutionTreeExtractor>()
    private val groundingEngine = mockk<HybridGroundingEngine>()
    private val gatekeeper = FinancialGatekeeperImpl()
    private val gestureExecutor = mockk<GestureExecutor>()
    private val textInputExecutor = mockk<TextInputExecutor>()
    private val watchdog = mockk<SelfHealingWatchdog>(relaxed = true)
    private val serviceHolder = mockk<AccessibilityServiceHolder>(relaxed = true)

    private lateinit var coordinator: HelixGuiCoordinator

    private fun createMockNode(
        id: String,
        text: String?,
        className: String,
        bounds: ElementBounds,
        isClickable: Boolean = false,
        isEditable: Boolean = false,
        priority: Int = 0
    ): ExecutionNode {
        return ExecutionNode(
            nodeId = id,
            text = text,
            contentDescription = null,
            resourceId = if (isEditable) "edit_search" else null,
            className = className,
            bounds = bounds,
            isClickable = isClickable,
            isEditable = isEditable,
            isCheckable = false,
            isChecked = false,
            isScrollable = false,
            isEnabled = true,
            isVisibleToUser = true,
            isPassword = false,
            priority = priority
        )
    }

    @Before
    fun setUp() {
        coEvery { gestureExecutor.execute(any(), any()) } returns GestureResult.Completed(50L)
        coEvery { textInputExecutor.injectText(any(), any(), any()) } returns true
        every { textInputExecutor.lastHonestReason } returns null
        every { watchdog.inspectStep(any(), any(), any(), any(), any()) } returns WatchdogVerdict.NormalProgress(1, 0L)

        coordinator = HelixGuiCoordinatorImpl(
            intentDispatcher = intentDispatcher,
            treeExtractor = treeExtractor,
            groundingEngine = groundingEngine,
            gatekeeper = gatekeeper,
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            watchdog = watchdog,
            serviceHolder = serviceHolder,
            rootNodeProvider = { mockk(relaxed = true) },
            currentPackageProvider = { "com.example.app" },
            screenWidthProvider = { 1080 },
            screenHeightProvider = { 2400 }
        )
    }

    @Test
    fun testExecuteTask_intentPreCheckDirectSuccess() = runTest {
        val action = CoordinatorAction.Intent(IntentAction.OpenSettings(SettingType.GENERAL))
        every { intentDispatcher.dispatch(action.action) } returns DispatchResult.Success(Intent())

        val result = coordinator.executeTask(
            goal = "打开系统设置",
            actions = listOf(action)
        )

        assertTrue(result is TaskExecutionResult.Success)
        assertEquals(1, (result as TaskExecutionResult.Success).stepsTaken)
        assertTrue(coordinator.state.value is TaskExecutionState.Completed)
    }

    @Test
    fun testExecuteTask_multiStepClickAndInput_success() = runTest {
        val mockNodes = listOf(
            createMockNode("1", "搜索", "android.widget.Button", ElementBounds(100, 200, 300, 300), isClickable = true, priority = 4),
            createMockNode("2", "", "android.widget.EditText", ElementBounds(100, 400, 800, 500), isEditable = true, priority = 5)
        )

        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.example.app",
            activityName = "MainActivity",
            nodes = mockNodes,
            totalDiscovered = 2,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 15L
        )

        coEvery {
            groundingEngine.locate(match { it.text == "搜索" }, any(), any(), any(), any())
        } returns GroundingResult.Found(
            targetPoint = Point2D(200f, 250f),
            bounds = NormalizedRect(100f, 100f, 300f, 200f),
            source = GroundingSource.ACCESSIBILITY_EXACT,
            confidence = 1.0f,
            refNode = UiElementNode(
                className = "android.widget.Button",
                text = "搜索",
                bounds = ElementBounds(100, 200, 300, 300),
                isClickable = true
            )
        )

        coEvery {
            groundingEngine.locate(match { it.resourceId == "edit_search" }, any(), any(), any(), any())
        } returns GroundingResult.Found(
            targetPoint = Point2D(450f, 450f),
            bounds = NormalizedRect(100f, 200f, 800f, 300f),
            source = GroundingSource.ACCESSIBILITY_EXACT,
            confidence = 1.0f,
            refNode = UiElementNode(
                className = "android.widget.EditText",
                resourceId = "edit_search",
                bounds = ElementBounds(100, 400, 800, 500)
            )
        )

        val actions = listOf(
            CoordinatorAction.Click(GroundingQuery(text = "搜索"), description = "点击搜索按钮"),
            CoordinatorAction.Type(GroundingQuery(resourceId = "edit_search"), text = "故宫博物院", description = "输入地点"),
            CoordinatorAction.Finish(summary = "搜索完成")
        )

        val result = coordinator.executeTask("搜索故宫路线", actions)

        assertTrue(result is TaskExecutionResult.Success)
        assertEquals(3, (result as TaskExecutionResult.Success).stepsTaken)
        assertTrue(coordinator.state.value is TaskExecutionState.Completed)

        coVerify(exactly = 1) { gestureExecutor.execute(any(), any()) }
        coVerify(exactly = 1) { textInputExecutor.injectText(any(), any(), "故宫博物院") }
    }

    @Test
    fun testExecuteTask_financialGatekeeper_passwordOrPayment_triggersCriticalBlock() = runTest {
        val mockNodes = listOf(
            createMockNode("1", "立即支付", "android.widget.Button", ElementBounds(100, 800, 900, 950), isClickable = true, priority = 5)
        )

        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.example.app",
            activityName = "MainActivity",
            nodes = mockNodes,
            totalDiscovered = 1,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 10L
        )

        coEvery {
            groundingEngine.locate(any(), any(), any(), any(), any())
        } returns GroundingResult.Found(
            targetPoint = Point2D(500f, 875f),
            bounds = NormalizedRect(100f, 400f, 900f, 450f),
            source = GroundingSource.ACCESSIBILITY_EXACT,
            confidence = 1.0f,
            refNode = UiElementNode(
                className = "android.widget.Button",
                text = "立即支付",
                bounds = ElementBounds(100, 800, 900, 950),
                isClickable = true
            )
        )

        val actions = listOf(
            CoordinatorAction.Click(GroundingQuery(text = "立即支付"), description = "点击立即支付按钮")
        )

        val result = coordinator.executeTask("购买商品", actions)

        assertTrue(result is TaskExecutionResult.Failure)
        val state = coordinator.state.value
        assertTrue(state is TaskExecutionState.Failed)
        assertTrue((state as TaskExecutionState.Failed).errorReason.contains("零信任"))

        // 严格断言物理手势未被下发
        coVerify(exactly = 0) { gestureExecutor.execute(any(), any()) }
    }

    @Test
    fun testExecuteTask_highRiskOrder_requiresConfirmation() = runTest {
        val mockNodes = listOf(
            createMockNode("1", "提交订单", "android.widget.Button", ElementBounds(100, 800, 900, 950), isClickable = true, priority = 5)
        )

        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.example.app",
            activityName = "MainActivity",
            nodes = mockNodes,
            totalDiscovered = 1,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 10L
        )

        coEvery {
            groundingEngine.locate(any(), any(), any(), any(), any())
        } returns GroundingResult.Found(
            targetPoint = Point2D(500f, 875f),
            bounds = NormalizedRect(100f, 400f, 900f, 450f),
            source = GroundingSource.ACCESSIBILITY_EXACT,
            confidence = 1.0f,
            refNode = UiElementNode(
                className = "android.widget.Button",
                text = "提交订单",
                bounds = ElementBounds(100, 800, 900, 950),
                isClickable = true
            )
        )

        val actions = listOf(
            CoordinatorAction.Click(GroundingQuery(text = "提交订单"), description = "点击提交订单"),
            CoordinatorAction.Finish(summary = "下单成功")
        )

        val job = launch {
            coordinator.executeTask("提交外卖订单", actions)
        }

        // 推进调度器直到遇到挂起 (即在等待用户确认处挂起)
        testScheduler.runCurrent()

        val currentState = coordinator.state.value
        assertTrue("状态机必须进入 AwaitingUserConfirmation", currentState is TaskExecutionState.AwaitingUserConfirmation)

        val token = (currentState as TaskExecutionState.AwaitingUserConfirmation).token
        coordinator.confirmPendingAction(token)

        testScheduler.advanceUntilIdle()
        job.join()

        assertTrue(coordinator.state.value is TaskExecutionState.Completed)
        coVerify(exactly = 1) { gestureExecutor.execute(any(), any()) }
    }

    @Test
    fun testExecuteTask_popupBlocking_triggersWatchdogDismissPopupSelfHealing() = runTest {
        val popupNodes = listOf(
            createMockNode("p1", "广告促销弹窗", "android.widget.TextView", ElementBounds(100, 100, 900, 300)),
            createMockNode("p2", "跳过", "android.widget.Button", ElementBounds(800, 150, 900, 250), isClickable = true, priority = 4)
        )

        val targetNodes = listOf(
            createMockNode("t1", "我的主页", "android.widget.Button", ElementBounds(100, 500, 400, 600), isClickable = true, priority = 4)
        )

        // 第一轮返回含弹窗的树，第二轮返回切除后的目标树
        var dumpCount = 0
        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } answers {
            dumpCount++
            if (dumpCount == 1) {
                TreeDumpResult.Ok(
                    packageName = "com.example.app",
                    activityName = "MainActivity",
                    nodes = popupNodes,
                    totalDiscovered = popupNodes.size,
                    isTruncated = false,
                    omittedCount = 0,
                    dumpDurationMs = 10L
                )
            } else {
                TreeDumpResult.Ok(
                    packageName = "com.example.app",
                    activityName = "MainActivity",
                    nodes = targetNodes,
                    totalDiscovered = targetNodes.size,
                    isTruncated = false,
                    omittedCount = 0,
                    dumpDurationMs = 10L
                )
            }
        }

        // 第一轮找不到目标，触发 watchdog 弹窗诊断
        coEvery {
            groundingEngine.locate(match { it.text == "我的主页" }, any(), any(), any(), any())
        } answers {
            val nodes = secondArg<List<UiElementNode>>()
            val found = nodes.firstOrNull { it.text == "我的主页" }
            if (found != null) {
                GroundingResult.Found(
                    targetPoint = Point2D(250f, 550f),
                    bounds = NormalizedRect(100f, 250f, 400f, 300f),
                    source = GroundingSource.ACCESSIBILITY_EXACT,
                    confidence = 1.0f,
                    refNode = found
                )
            } else {
                GroundingResult.NotFound("未在当前树中找到我的主页")
            }
        }

        every {
            watchdog.inspectStep(any(), any(), any(), any(), any())
        } returns WatchdogVerdict.Recoverable(
            anomaly = AnomalyType.POPUP_BLOCKING,
            suggestedAction = HealingAction.DismissPopup("跳过"),
            attemptCount = 1
        )

        val actions = listOf(
            CoordinatorAction.Click(GroundingQuery(text = "我的主页"), description = "点击主页"),
            CoordinatorAction.Finish("进入主页")
        )

        val result = coordinator.executeTask("打开主页", actions)

        assertTrue(result is TaskExecutionResult.Success)
        // 验证执行了 1 次自愈点击（跳过）+ 1 次目标点击（我的主页）
        coVerify(exactly = 2) { gestureExecutor.execute(any(), any()) }
    }

    @Test
    fun testExecuteTask_exceedsMaxSteps_failsBounded() = runTest {
        // 创建超过 MAX_STEPS (12) 的任务序列
        val actions = (1..15).map { index ->
            CoordinatorAction.Click(GroundingQuery(text = "步骤 $index"), description = "执行步骤 $index")
        }

        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.example.app",
            activityName = "MainActivity",
            nodes = emptyList(),
            totalDiscovered = 0,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 5L
        )
        coEvery { groundingEngine.locate(any(), any(), any(), any(), any()) } returns GroundingResult.Found(
            targetPoint = Point2D(50f, 50f),
            bounds = NormalizedRect(0f, 0f, 100f, 100f),
            source = GroundingSource.ACCESSIBILITY_EXACT,
            confidence = 1.0f,
            refNode = UiElementNode(
                className = "android.widget.Button",
                text = "步骤",
                bounds = ElementBounds(0, 0, 100, 100)
            )
        )

        val result = coordinator.executeTask("超长任务", actions, maxSteps = 12)

        assertTrue(result is TaskExecutionResult.Failure)
        val state = coordinator.state.value
        assertTrue(state is TaskExecutionState.Failed)
        assertTrue((state as TaskExecutionState.Failed).errorReason.contains("最大执行步数"))
    }

    @Test
    fun testExecuteTask_privacySensitiveApp_triggersBlackoutFailure() = runTest {
        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Empty(
            reason = EmptyReason.PRIVACY_BLACKOUT,
            message = "目标应用命中银行/支付物理安全黑名单"
        )

        val actions = listOf(
            CoordinatorAction.Click(GroundingQuery(text = "转账"), description = "点击转账")
        )

        val result = coordinator.executeTask("银行转账", actions)

        assertTrue(result is TaskExecutionResult.Failure)
        assertTrue((result as TaskExecutionResult.Failure).reason.contains("安全熔断"))
        assertTrue(coordinator.state.value is TaskExecutionState.Failed)
    }

    @Test
    fun testExecuteTask_typeWithDraftConfirmation_injectsEditedDraft() = runTest {
        val mockNodes = listOf(
            createMockNode("1", "确认下单备注", "android.widget.EditText", ElementBounds(100, 800, 900, 950), isEditable = true, priority = 5)
        )

        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.example.app",
            activityName = "MainActivity",
            nodes = mockNodes,
            totalDiscovered = 1,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 10L
        )

        coEvery {
            groundingEngine.locate(any(), any(), any(), any(), any())
        } returns GroundingResult.Found(
            targetPoint = Point2D(500f, 875f),
            bounds = NormalizedRect(100f, 400f, 900f, 450f),
            source = GroundingSource.ACCESSIBILITY_EXACT,
            confidence = 1.0f,
            refNode = UiElementNode(
                className = "android.widget.EditText",
                text = "确认下单备注",
                bounds = ElementBounds(100, 800, 900, 950)
            )
        )

        coEvery { textInputExecutor.injectText(any(), any(), any()) } returns true

        val actions = listOf(
            CoordinatorAction.Type(GroundingQuery(text = "确认下单备注"), text = "原始草稿内容", description = "输入备注")
        )

        val job = launch {
            coordinator.executeTask("确认下单备注输入", actions)
        }

        testScheduler.runCurrent()

        val state = coordinator.state.value
        assertTrue("状态机必须进入 AwaitingUserConfirmation", state is TaskExecutionState.AwaitingUserConfirmation)
        val confirmState = state as TaskExecutionState.AwaitingUserConfirmation

        assertEquals("原始草稿内容", confirmState.draftContent)
        assertEquals("输入文本草稿确认", confirmState.draftTitle)
        assertNotNull(confirmState.onConfirmedWithDraft)

        // 用户在草稿卡中编辑文字并确认
        confirmState.onConfirmedWithDraft?.invoke("修改后的安全内容")

        testScheduler.advanceUntilIdle()
        job.join()

        assertTrue(coordinator.state.value is TaskExecutionState.Completed)
        coVerify(exactly = 1) {
            textInputExecutor.injectText(any(), any(), "修改后的安全内容")
        }
    }

    @Test
    fun testCancelTask_resetsToIdle() = runTest {
        coordinator.cancelCurrentTask()
        assertEquals(TaskExecutionState.Idle, coordinator.state.value)
    }

    @Test
    fun testExecuteTask_awaitingConfirmationTimeout_failsWithTimeout() = runTest {
        val customCoordinator = HelixGuiCoordinatorImpl(
            intentDispatcher = intentDispatcher,
            treeExtractor = treeExtractor,
            groundingEngine = groundingEngine,
            gatekeeper = gatekeeper,
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            watchdog = watchdog,
            confirmationTimeoutMs = 150L
        )

        val mockNodes = listOf(
            createMockNode("1", "确认下单", "android.widget.Button", ElementBounds(100, 800, 900, 950), priority = 5)
        )

        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.example.app",
            activityName = "MainActivity",
            nodes = mockNodes,
            totalDiscovered = 1,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 10L
        )

        coEvery {
            groundingEngine.locate(any(), any(), any(), any(), any())
        } returns GroundingResult.Found(
            targetPoint = Point2D(500f, 875f),
            bounds = NormalizedRect(100f, 400f, 900f, 450f),
            source = GroundingSource.ACCESSIBILITY_EXACT,
            confidence = 1.0f,
            refNode = UiElementNode(
                className = "android.widget.Button",
                text = "确认下单",
                bounds = ElementBounds(100, 800, 900, 950)
            )
        )

        var executionResult: TaskExecutionResult? = null
        val job = launch {
            executionResult = customCoordinator.executeTask(
                "提交高危订单",
                listOf(CoordinatorAction.Click(GroundingQuery(text = "确认下单"), description = "点击提交订单"))
            )
        }

        testScheduler.runCurrent()
        assertTrue(customCoordinator.state.value is TaskExecutionState.AwaitingUserConfirmation)

        // 时间流逝超过 150ms 超时阈值，且用户未做确认
        testScheduler.advanceTimeBy(300L)
        testScheduler.advanceUntilIdle()
        job.join()

        assertTrue("任务执行结果必须为 Failure", executionResult is TaskExecutionResult.Failure)
        assertTrue(
            "失败原因必须包含超时提示",
            (executionResult as TaskExecutionResult.Failure).reason.contains("超时")
        )
        assertTrue(customCoordinator.state.value is TaskExecutionState.Failed)
    }

    @Test
    fun testExecuteTask_whenAccessibilityNotFound_triggersScreenCaptureAndOcrGrounding() = runTest {
        val mockScreenCapture = mockk<ScreenCaptureManager>()
        val dummyBytes = byteArrayOf(10, 20)
        coEvery { mockScreenCapture.captureScreen() } returns FrameCaptureResult(dummyBytes, 1080, 2400)

        val ocrCoordinator = HelixGuiCoordinatorImpl(
            intentDispatcher = intentDispatcher,
            treeExtractor = treeExtractor,
            groundingEngine = groundingEngine,
            gatekeeper = gatekeeper,
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            watchdog = watchdog,
            screenWidthProvider = { 1080 },
            screenHeightProvider = { 2400 },
            screenCaptureManager = mockScreenCapture
        )

        // 第一次无障碍树抽取成功，但无障碍节点树里没有目标文字（例如自绘 Canvas）
        val mockNodes = listOf(
            createMockNode("root", "主页容器", "android.widget.FrameLayout", ElementBounds(0, 0, 1080, 2400))
        )
        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.flutter.app",
            activityName = "FlutterActivity",
            nodes = mockNodes,
            totalDiscovered = 1,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 5L
        )

        // 1. 无截图调用时（L0 无障碍直达）：返回 NotFound
        coEvery {
            groundingEngine.locate(any(), any(), screenshot = null, any(), any())
        } returns GroundingResult.NotFound("未在无障碍树中找到")

        // 2. 带截图调用时（L1 OCR 视觉接地）：成功找到
        coEvery {
            groundingEngine.locate(any(), any(), screenshot = dummyBytes, any(), any())
        } returns GroundingResult.Found(
            targetPoint = Point2D(300f, 600f),
            bounds = NormalizedRect(200f, 500f, 400f, 700f),
            source = GroundingSource.VISUAL_OCR,
            confidence = 0.90f
        )

        val actions = listOf(
            CoordinatorAction.Click(GroundingQuery(text = "自绘按钮"), description = "点击自绘按钮")
        )

        val result = ocrCoordinator.executeTask("测试自绘按钮点击", actions)

        assertTrue("带 OCR 截屏接地应执行成功", result is TaskExecutionResult.Success)
        coVerify(exactly = 1) { mockScreenCapture.captureScreen() }
        coVerify(exactly = 1) { gestureExecutor.execute(any(), any()) }
    }

    @Test
    fun testExecuteTask_macroWithPressKeyAndDelay_dispatchesExecutionBackend() = runTest {
        val mockBackend = mockk<ExecutionBackend>(relaxed = true)
        coEvery { mockBackend.isAvailable() } returns true
        coEvery { mockBackend.sendKeycode(66) } returns true

        val macroCoordinator = HelixGuiCoordinatorImpl(
            intentDispatcher = intentDispatcher,
            treeExtractor = treeExtractor,
            groundingEngine = groundingEngine,
            gatekeeper = gatekeeper,
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            watchdog = watchdog,
            executionBackend = mockBackend
        )

        val actions = listOf(
            CoordinatorAction.Delay(50L, "稍作等待"),
            CoordinatorAction.PressKey(66, "ENTER", "按回车")
        )

        val result = macroCoordinator.executeTask("执行宏动作", actions)

        assertTrue(result is TaskExecutionResult.Success)
        coVerify(exactly = 1) { mockBackend.sendKeycode(66) }
    }
}
