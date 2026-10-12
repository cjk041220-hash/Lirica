package com.lirica.android.automation.coordinator

import com.lirica.android.automation.observe.ObservationLevel
import com.lirica.android.automation.observe.ObservationNode
import com.lirica.android.automation.observe.ObservationSnapshot
import com.lirica.android.automation.observe.NodeRole
import com.lirica.android.automation.policy.ActionExecutionMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单步决策器回归（M5.2 §5.1.2）。
 *
 * 断言重点：模型**只能通过 refId 指代目标**，绝不能自造坐标。
 * 这是接地层安全审查得以成立的前提（拿不到目标语义就无法判定敏感控件）。
 */
class DynamicStepPlannerTest {

    private fun snapshot(nodes: List<ObservationNode>): ObservationSnapshot = ObservationSnapshot(
        level = ObservationLevel.SEMANTIC_TREE,
        packageName = "com.example.app",
        activityName = "MainActivity",
        nodes = nodes,
        truncatedCount = 0,
        promptText = "pkg=com.example.app\nn0 BUTTON[C] \"搜索\" @(500,100)",
        capturedAtMs = 1_000L
    )

    private fun node(refId: String, text: String?, desc: String? = null, x: Int = 500, y: Int = 100) =
        ObservationNode(
            refId = refId,
            text = text,
            contentDescription = desc,
            resourceId = "com.example:id/" + refId,
            className = "android.widget.Button",
            role = NodeRole.BUTTON,
            normalizedX = x,
            normalizedY = y,
            isClickable = true,
            isEditable = false,
            isEnabled = true,
            isChecked = false,
            isScrollable = false
        )

    private fun context(
        observation: ObservationSnapshot,
        stepIndex: Int = 1,
        history: List<StepRecord> = emptyList(),
        mode: ActionExecutionMode = ActionExecutionMode.SHARED
    ) = PlanningContext(
        goal = "打开设置里的无障碍",
        stepIndex = stepIndex,
        maxSteps = 12,
        observation = observation,
        history = history,
        mode = mode
    )

    @Test
    fun testPromptCarriesGoalHistoryAndObservation() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val prompt = planner.buildPrompt(
            context(
                observation = snapshot(listOf(node("n0", "搜索"))),
                stepIndex = 3,
                history = listOf(
                    StepRecord(
                        1,
                        "点击 搜索",
                        com.lirica.android.automation.verify.ActionVerification.NoVisibleEffect(null, null),
                        "应出现搜索框"
                    )
                )
            )
        )
        assertTrue(prompt.contains("打开设置里的无障碍"))
        assertTrue(prompt.contains("第 3/12 步"))
        assertTrue(prompt.contains("未见变化"))
        assertTrue(prompt.contains("pkg=com.example.app"))
        assertTrue(prompt.contains("只输出一个 JSON 对象"))
    }

    @Test
    fun testPromptMarksRoamingMode() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val prompt = planner.buildPrompt(
            context(snapshot(listOf(node("n0", "搜索"))), mode = ActionExecutionMode.ROAMING)
        )
        assertTrue("漫游模式必须在 Prompt 里显式约束", prompt.contains("漫游只读"))
    }

    @Test
    fun testParseClickResolvesRefToGroundingQuery() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val observation = snapshot(listOf(node("n0", "搜索", x = 250, y = 80)))
        val step = planner.parse(
            """{"think":"先搜索","action":{"type":"click","ref":"n0"},"expect":"出现搜索框"}""",
            observation
        )
        assertTrue(step != null)
        val action = step!!.action as CoordinatorAction.Click
        assertEquals("搜索", action.query.text)
        assertEquals("com.example:id/n0", action.query.resourceId)
        assertEquals(250, action.query.normalizedAnchorX)
        assertEquals(80, action.query.normalizedAnchorY)
        assertEquals("n0", step.refId)
        assertEquals("出现搜索框", step.expectation)
    }

    @Test
    fun testParseTypeCarriesText() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val step = planner.parse(
            """{"action":{"type":"type","ref":"n1","text":"无障碍"}}""",
            snapshot(listOf(node("n1", "输入框")))
        )
        val action = step!!.action as CoordinatorAction.Type
        assertEquals("无障碍", action.text)
    }

    @Test
    fun testParseSwipeAndBackAndWait() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val observation = snapshot(listOf(node("n0", "列表")))
        assertEquals(
            SwipeDirection.DOWN,
            ((planner.parse("""{"action":{"type":"swipe","direction":"down"}}""", observation)!!.action) as CoordinatorAction.Swipe).direction
        )
        assertTrue(planner.parse("""{"action":{"type":"back"}}""", observation)!!.action is CoordinatorAction.Backtrack)
        val wait = planner.parse("""{"action":{"type":"wait","delay_ms":500}}""", observation)!!.action as CoordinatorAction.Delay
        assertEquals(500L, wait.delayMs)
    }

    @Test
    fun testParseWaitClampsDelay() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val observation = snapshot(listOf(node("n0", "x")))
        val tooLong = planner.parse("""{"action":{"type":"wait","delay_ms":999999}}""", observation)!!.action as CoordinatorAction.Delay
        assertEquals(LlmDynamicStepPlanner.MAX_WAIT_MS, tooLong.delayMs)
        val tooShort = planner.parse("""{"action":{"type":"wait","delay_ms":1}}""", observation)!!.action as CoordinatorAction.Delay
        assertEquals(100L, tooShort.delayMs)
    }

    @Test
    fun testParseFinish() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val step = planner.parse("""{"action":{"type":"finish","summary":"已经打开了"}}""", snapshot(listOf(node("n0", "x"))))
        assertTrue(step!!.finish)
        assertEquals("已经打开了", step.summary)
        assertNull(step.action)
    }

    @Test
    fun testParseToleratesMarkdownWrapping() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val raw = "好的，这是结果：\n```json\n{\"action\":{\"type\":\"back\"}}\n```\n希望有帮助"
        val step = planner.parse(raw, snapshot(listOf(node("n0", "x"))))
        assertTrue(step!!.action is CoordinatorAction.Backtrack)
    }

    @Test
    fun testParseRejectsUnknownTypeOrMissingFields() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val observation = snapshot(listOf(node("n0", "x")))
        assertNull("未知动作类型必须拒绝而不是猜", planner.parse("""{"action":{"type":"teleport"}}""", observation))
        assertNull("缺 type 必须拒绝", planner.parse("""{"action":{"ref":"n0"}}""", observation))
        assertNull("type 缺 text 必须拒绝", planner.parse("""{"action":{"type":"type","ref":"n0"}}""", observation))
        assertNull("非 JSON 必须拒绝", planner.parse("我建议你点一下搜索按钮", observation))
        assertNull("缺少 action 对象必须拒绝", planner.parse("""{"think":"随便看看"}""", observation))
    }

    @Test
    fun testUnknownRefIsRejectedInsteadOfFabricatingQuery() {
        // 引用必须在本轮观测里真实存在：伪造一个 semanticDescription="n9" 的查询
        // 只会让接地层白跑一趟并产出误导性日志，不如明确失败让闭环刷新观测
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val observation = snapshot(listOf(node("n0", "搜索")))
        assertNull(planner.parse("""{"action":{"type":"click","ref":"n9"}}""", observation))
        assertNull(planner.parse("""{"action":{"type":"type","ref":"n9","text":"x"}}""", observation))
    }

    @Test
    fun testParseAcceptsFieldAliases() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val observation = snapshot(listOf(node("n0", "搜索")))
        assertTrue(planner.parse("""{"action":{"type":"click","ref_id":"n0"}}""", observation) != null)
        assertTrue(planner.parse("""{"action":{"type":"click","refId":"n0"}}""", observation) != null)
    }

    @Test
    fun testParsePressKeyAndSearchEnterActions() {
        val planner = LlmDynamicStepPlanner(chatInvoker = { "" })
        val observation = snapshot(listOf(node("n0", "搜索框")))

        // 1. press_key with symbol
        val pressStep = planner.parse("""{"action":{"type":"press_key","key":"ENTER"}}""", observation)
        assertTrue(pressStep!!.action is CoordinatorAction.PressKey)
        val pressAction = pressStep.action as CoordinatorAction.PressKey
        assertEquals(android.view.KeyEvent.KEYCODE_ENTER, pressAction.keyCode)

        // 2. shorthand enter
        val enterStep = planner.parse("""{"action":{"type":"enter"}}""", observation)
        assertTrue(enterStep!!.action is CoordinatorAction.PressKey)
        assertEquals(android.view.KeyEvent.KEYCODE_ENTER, (enterStep.action as CoordinatorAction.PressKey).keyCode)

        // 3. shorthand search
        val searchStep = planner.parse("""{"action":{"type":"search"}}""", observation)
        assertTrue(searchStep!!.action is CoordinatorAction.PressKey)
        assertEquals(android.view.KeyEvent.KEYCODE_SEARCH, (searchStep.action as CoordinatorAction.PressKey).keyCode)
    }

    @Test
    fun testPlanReturnsNullWhenInvokerThrows() = runTest {
        val planner = LlmDynamicStepPlanner(chatInvoker = { throw IllegalStateException("网络断了") })
        assertNull(planner.plan(context(snapshot(listOf(node("n0", "搜索"))))))
    }

    @Test
    fun testPlanRetriesParseThenSucceeds() = runTest {
        var calls = 0
        val planner = LlmDynamicStepPlanner(chatInvoker = {
            calls++
            if (calls == 1) "抱歉，我不太确定" else """{"action":{"type":"back"}}"""
        })
        val step = planner.plan(context(snapshot(listOf(node("n0", "搜索")))))
        assertTrue(step!!.action is CoordinatorAction.Backtrack)
        assertEquals(2, calls)
    }

    @Test
    fun testPlanReportsEstimatedTokenUsage() = runTest {
        var promptTokens = 0
        var completionTokens = 0
        val planner = LlmDynamicStepPlanner(
            chatInvoker = { """{"action":{"type":"back"}}""" },
            usageSink = { usage ->
                promptTokens += usage.promptTokens
                completionTokens += usage.completionTokens
            }
        )
        planner.plan(context(snapshot(listOf(node("n0", "搜索")))))
        assertTrue("Prompt token 估算必须上报（复审 R8）", promptTokens > 0)
        assertTrue(completionTokens > 0)
    }
}
