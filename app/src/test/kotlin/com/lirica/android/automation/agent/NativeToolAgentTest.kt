package com.lirica.android.automation.agent

import com.lirica.android.automation.capture.FrameCaptureResult
import com.lirica.android.automation.capture.ScreenCaptureManager
import com.lirica.android.automation.coordinator.CoordinatorAction
import com.lirica.android.automation.coordinator.HelixGuiCoordinator
import com.lirica.android.automation.coordinator.TaskExecutionResult
import com.lirica.android.automation.grounding.NormalizedRect
import com.lirica.android.automation.grounding.VisualDetection
import com.lirica.android.automation.grounding.VisualElementDetector
import com.lirica.android.automation.intent.DispatchResult
import com.lirica.android.automation.intent.IntentAction
import com.lirica.android.automation.intent.IntentActionDispatcher
import com.lirica.android.database.entity.ScheduledAlarmEntity
import com.lirica.android.network.model.ToolCall
import com.lirica.android.network.model.ToolCallFunction
import com.lirica.android.proactivity.ScheduleWakeManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
class NativeToolAgentTest {

    private val coordinator = mockk<HelixGuiCoordinator>()
    private val intentDispatcher = mockk<IntentActionDispatcher>()
    private val scheduleWakeManager = mockk<ScheduleWakeManager>()
    private lateinit var agent: NativeToolAgent

    @Before
    fun setUp() {
        agent = NativeToolAgent(
            coordinator = coordinator,
            intentDispatcher = intentDispatcher,
            scheduleWakeManager = scheduleWakeManager
        )
    }

    @Test
    fun testDeclaredTools_containsAllCoreTools() {
        val tools = agent.declaredTools
        assertEquals(7, tools.size)
        val toolNames = tools.map { it.function.name }
        assertTrue(toolNames.contains("open_app"))
        assertTrue(toolNames.contains("click_element"))
        assertTrue(toolNames.contains("input_text"))
        assertTrue(toolNames.contains("swipe"))
        assertTrue(toolNames.contains("task_finish"))
        assertTrue(toolNames.contains("schedule_wake"))
        assertTrue(toolNames.contains("execute_macro"))

        tools.forEach { tool ->
            assertNotNull(tool.function.description)
            assertNotNull(tool.function.parameters)
        }
    }

    @Test
    fun testExecute_openApp_dispatchesLaunchApp() = runTest {
        val call = ToolCall(
            id = "call_1",
            function = ToolCallFunction(
                name = "open_app",
                arguments = """{"app_name":"高德地图","package_name":"com.autonavi.minimap"}"""
            )
        )

        every {
            intentDispatcher.dispatch(any())
        } returns DispatchResult.Success(mockk(relaxed = true))

        val result = agent.executeToolCall(call)

        assertTrue(result.isSuccess)
        assertEquals("call_1", result.toolCallId)
        assertTrue(result.output.contains("高德地图"))
    }

    @Test
    fun testExecute_openApp_naturalLanguageChineseName_resolvesPackageName() = runTest {
        val call = ToolCall(
            id = "call_nl_1",
            function = ToolCallFunction(
                name = "open_app",
                arguments = """{"app_name":"微信"}"""
            )
        )

        every {
            intentDispatcher.dispatch(any())
        } returns DispatchResult.Success(mockk(relaxed = true))

        val result = agent.executeToolCall(call)

        assertTrue(result.isSuccess)
        io.mockk.verify(exactly = 1) {
            intentDispatcher.dispatch(
                match { it is IntentAction.LaunchApp && it.packageName == "com.tencent.mm" }
            )
        }
    }

    @Test
    fun testExecute_clickElement_delegatesToCoordinator() = runTest {
        val call = ToolCall(
            id = "call_2",
            function = ToolCallFunction(
                name = "click_element",
                arguments = """{"text":"搜索路线","resource_id":"btn_search","description":"点击搜索"}"""
            )
        )

        coEvery {
            coordinator.executeTask(any(), any(), any())
        } returns TaskExecutionResult.Success("点击成功", 1)

        val result = agent.executeToolCall(call)

        assertTrue(result.isSuccess)
        assertEquals("call_2", result.toolCallId)
        assertTrue(result.output.contains("点击成功"))

        coVerify(exactly = 1) {
            coordinator.executeTask(
                goal = match { it.contains("点击") },
                actions = match { actions ->
                    actions.size == 1 && actions[0] is CoordinatorAction.Click
                },
                maxSteps = any()
            )
        }
    }

    @Test
    fun testExecute_inputText_delegatesToCoordinator() = runTest {
        val call = ToolCall(
            id = "call_3",
            function = ToolCallFunction(
                name = "input_text",
                arguments = """{"text":"故宫博物院","target_text":"请输入目的地"}"""
            )
        )

        coEvery {
            coordinator.executeTask(any(), any(), any())
        } returns TaskExecutionResult.Success("输入成功", 1)

        val result = agent.executeToolCall(call)

        assertTrue(result.isSuccess)
        assertEquals("call_3", result.toolCallId)
        assertTrue(result.output.contains("输入成功"))

        coVerify(exactly = 1) {
            coordinator.executeTask(
                goal = match { it.contains("键入") || it.contains("输入") },
                actions = match { actions ->
                    actions.size == 1 && actions[0] is CoordinatorAction.Type && (actions[0] as CoordinatorAction.Type).text == "故宫博物院"
                },
                maxSteps = any()
            )
        }
    }

    @Test
    fun testExecute_taskFinish_returnsCompletionSummary() = runTest {
        val call = ToolCall(
            id = "call_4",
            function = ToolCallFunction(
                name = "task_finish",
                arguments = """{"summary":"已成功搜索故宫路线并展示在屏幕上","success":true}"""
            )
        )

        val result = agent.executeToolCall(call)

        assertTrue(result.isSuccess)
        assertEquals("call_4", result.toolCallId)
        assertTrue(result.output.contains("已成功搜索故宫路线"))
    }

    @Test
    fun testExecute_malformedJson_returnsSafeErrorResult() = runTest {
        val call = ToolCall(
            id = "call_5",
            function = ToolCallFunction(
                name = "open_app",
                arguments = "{malformed json---"
            )
        )

        val result = agent.executeToolCall(call)

        assertFalse(result.isSuccess)
        assertEquals("call_5", result.toolCallId)
        assertTrue(result.output.contains("解析") || result.output.contains("错误") || result.output.contains("失败"))
    }

    @Test
    fun testExecute_unknownTool_returnsSafeErrorResult() = runTest {
        val call = ToolCall(
            id = "call_6",
            function = ToolCallFunction(
                name = "unknown_teleport_action",
                arguments = "{}"
            )
        )

        val result = agent.executeToolCall(call)

        assertFalse(result.isSuccess)
        assertEquals("call_6", result.toolCallId)
        assertTrue(result.output.contains("未知"))
    }

    @Test
    fun testToolExecutionResult_convertsToToolChatMessage() {
        val executionResult = ToolExecutionResult(
            toolCallId = "call_7",
            isSuccess = true,
            output = "操作已完成"
        )

        val chatMessage = executionResult.toChatMessage()

        assertEquals("tool", chatMessage.role)
        assertEquals("call_7", chatMessage.toolCallId)
        assertEquals("操作已完成", chatMessage.content)
    }

    @Test
    fun testExecute_scheduleWake_delegatesToScheduleWakeManager() = runTest {
        val toolCall = ToolCall(
            id = "call_wake_1",
            type = "function",
            function = ToolCallFunction(
                name = "schedule_wake",
                arguments = "{\"delay_minutes\": 30, \"bot_note\": \"提醒喝水\", \"block_delivery\": true}"
            )
        )

        val scheduledEntity = ScheduledAlarmEntity(
            id = "alarm_1",
            triggerAt = System.currentTimeMillis() + 1800_000L,
            botNote = "提醒喝水",
            blockDelivery = true,
            status = ScheduledAlarmEntity.STATUS_PENDING
        )

        coEvery { scheduleWakeManager.scheduleWake(any(), "提醒喝水", true, any()) } returns scheduledEntity

        val result = agent.executeToolCall(toolCall)

        assertTrue(result.isSuccess)
        assertEquals("call_wake_1", result.toolCallId)
        assertTrue(result.output.contains("已成功设定自定闹钟约定"))
        assertTrue(result.output.contains("提醒喝水"))

        coVerify { scheduleWakeManager.scheduleWake(any(), "提醒喝水", true, any()) }
    }

    @Test
    fun testDeclaredTools_withScreenCaptureManager_includesReadScreen() {
        val mockScreenCapture = mockk<ScreenCaptureManager>()
        val agentWithCapture = NativeToolAgent(
            coordinator = coordinator,
            intentDispatcher = intentDispatcher,
            scheduleWakeManager = scheduleWakeManager,
            screenCaptureManager = mockScreenCapture
        )

        val tools = agentWithCapture.declaredTools
        assertEquals(8, tools.size)
        val toolNames = tools.map { it.function.name }
        assertTrue("应包含 read_screen 工具", toolNames.contains("read_screen"))
    }

    @Test
    fun testExecuteToolCall_readScreen_success() = runTest {
        val mockScreenCapture = mockk<ScreenCaptureManager>()
        val mockVisualDetector = mockk<VisualElementDetector>()

        val dummyBytes = byteArrayOf(1, 2, 3)
        val dummyFrame = FrameCaptureResult(dummyBytes, 1080, 2400)
        coEvery { mockScreenCapture.captureScreen() } returns dummyFrame

        val detections = listOf(
            VisualDetection("微信支付", NormalizedRect(100f, 200f, 400f, 300f)),
            VisualDetection("账单详情", NormalizedRect(100f, 400f, 400f, 500f))
        )
        coEvery { mockVisualDetector.detectAll(dummyBytes) } returns detections

        val agentWithOcr = NativeToolAgent(
            coordinator = coordinator,
            intentDispatcher = intentDispatcher,
            screenCaptureManager = mockScreenCapture,
            visualDetector = mockVisualDetector
        )

        val toolCall = ToolCall(
            id = "call_ocr_1",
            type = "function",
            function = ToolCallFunction(name = "read_screen", arguments = "{}")
        )

        val result = agentWithOcr.executeToolCall(toolCall)
        assertTrue(result.isSuccess)
        assertEquals("call_ocr_1", result.toolCallId)
        assertTrue(result.output.contains("微信支付"))
        assertTrue(result.output.contains("账单详情"))
        assertTrue(result.output.contains("2 项文本"))
    }

    @Test
    fun testExecuteToolCall_readScreen_whenCaptureNull_failsGracefully() = runTest {
        val mockScreenCapture = mockk<ScreenCaptureManager>()
        coEvery { mockScreenCapture.captureScreen() } returns null // 比如命中敏感金融拦截

        val agentWithOcr = NativeToolAgent(
            coordinator = coordinator,
            intentDispatcher = intentDispatcher,
            screenCaptureManager = mockScreenCapture
        )

        val toolCall = ToolCall(
            id = "call_ocr_2",
            type = "function",
            function = ToolCallFunction(name = "read_screen", arguments = "{}")
        )

        val result = agentWithOcr.executeToolCall(toolCall)
        assertFalse(result.isSuccess)
        assertTrue(result.output.contains("未能成功捕获屏幕"))
    }

    @Test
    fun testExecuteToolCall_executeMacro_sequentialSuccess() = runTest {
        coEvery {
            coordinator.executeTask("在网易云搜索并播放", any(), any())
        } returns TaskExecutionResult.Success("宏执行成功", 3)

        val macroArgs = """
            {
                "description": "在网易云搜索并播放",
                "actions": [
                    { "type": "click", "target_text": "搜索框" },
                    { "type": "type", "text": "海阔天空" },
                    { "type": "press_key", "key": "ENTER" }
                ]
            }
        """.trimIndent()

        val toolCall = ToolCall(
            id = "call_macro_1",
            type = "function",
            function = ToolCallFunction(name = "execute_macro", arguments = macroArgs)
        )

        val result = agent.executeToolCall(toolCall)
        assertTrue(result.isSuccess)
        assertEquals("call_macro_1", result.toolCallId)
        assertTrue(result.output.contains("宏动作分块执行成功 (共 3 步)"))

        coVerify(exactly = 1) {
            coordinator.executeTask("在网易云搜索并播放", match { actions ->
                actions.size == 3 &&
                    actions[0] is CoordinatorAction.Click &&
                    actions[1] is CoordinatorAction.Type &&
                    actions[2] is CoordinatorAction.PressKey
            }, any())
        }
    }

    @Test
    fun testExecuteToolCall_executeMacro_failureHaltsGracefully() = runTest {
        coEvery {
            coordinator.executeTask(any(), any(), any())
        } returns TaskExecutionResult.Failure("第 2 步元素未找到", 2)

        val macroArgs = """
            {
                "description": "测试中断宏",
                "actions": [
                    { "type": "click", "target_text": "第一步" },
                    { "type": "click", "target_text": "第二步" }
                ]
            }
        """.trimIndent()

        val toolCall = ToolCall(
            id = "call_macro_2",
            type = "function",
            function = ToolCallFunction(name = "execute_macro", arguments = macroArgs)
        )

        val result = agent.executeToolCall(toolCall)
        assertFalse(result.isSuccess)
        assertTrue(result.output.contains("第 2 步执行失败"))
    }

    @Test
    fun testExecute_clickElement_failurePassesHonestNarrative() = runTest {
        coEvery {
            coordinator.executeTask(any(), any(), any())
        } returns TaskExecutionResult.Failure(
            reason = "元素未找到",
            stepIndex = 0,
            honestNarrative = "那个按钮今天跟我捉迷藏，我找了半天也没看到"
        )

        val call = ToolCall(
            id = "call_click_fail",
            function = ToolCallFunction(
                name = "click_element",
                arguments = """{"target_text":"确认"}"""
            )
        )

        val result = agent.executeToolCall(call)
        assertFalse(result.isSuccess)
        assertEquals("那个按钮今天跟我捉迷藏，我找了半天也没看到", result.output)
    }

    @Test
    fun testExecute_executeMacro_failurePassesHonestNarrative() = runTest {
        coEvery {
            coordinator.executeTask(any(), any(), any())
        } returns TaskExecutionResult.Failure(
            reason = "超时",
            stepIndex = 1,
            honestNarrative = "宏动作中途卡壳了，请等我一下"
        )

        val call = ToolCall(
            id = "call_macro_fail",
            function = ToolCallFunction(
                name = "execute_macro",
                arguments = """{"description":"测试","actions":[{"type":"click","target_text":"步骤1"}]}"""
            )
        )

        val result = agent.executeToolCall(call)
        assertFalse(result.isSuccess)
        assertEquals("宏动作中途卡壳了，请等我一下", result.output)
    }
}
