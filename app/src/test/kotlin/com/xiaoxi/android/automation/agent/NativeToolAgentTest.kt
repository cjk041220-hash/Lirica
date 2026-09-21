package com.xiaoxi.android.automation.agent

import com.xiaoxi.android.automation.coordinator.CoordinatorAction
import com.xiaoxi.android.automation.coordinator.HelixGuiCoordinator
import com.xiaoxi.android.automation.coordinator.TaskExecutionResult
import com.xiaoxi.android.automation.intent.DispatchResult
import com.xiaoxi.android.automation.intent.IntentAction
import com.xiaoxi.android.automation.intent.IntentActionDispatcher
import com.xiaoxi.android.database.entity.ScheduledAlarmEntity
import com.xiaoxi.android.network.model.ToolCall
import com.xiaoxi.android.network.model.ToolCallFunction
import com.xiaoxi.android.proactivity.ScheduleWakeManager
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
        assertEquals(6, tools.size)
        val toolNames = tools.map { it.function.name }
        assertTrue(toolNames.contains("open_app"))
        assertTrue(toolNames.contains("click_element"))
        assertTrue(toolNames.contains("input_text"))
        assertTrue(toolNames.contains("swipe"))
        assertTrue(toolNames.contains("task_finish"))
        assertTrue(toolNames.contains("schedule_wake"))

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
}
