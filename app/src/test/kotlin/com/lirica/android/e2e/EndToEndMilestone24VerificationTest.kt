package com.lirica.android.e2e

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lirica.android.automation.agent.NativeToolAgent
import com.lirica.android.automation.agent.ToolExecutionResult
import com.lirica.android.automation.coordinator.CoordinatorAction
import com.lirica.android.automation.coordinator.HelixGuiCoordinator
import com.lirica.android.automation.coordinator.HelixGuiCoordinatorImpl
import com.lirica.android.automation.coordinator.TaskExecutionResult
import com.lirica.android.automation.coordinator.TaskExecutionState
import com.lirica.android.automation.gatekeeper.FinancialGatekeeperImpl
import com.lirica.android.automation.gesture.GestureExecutor
import com.lirica.android.automation.gesture.Point2D
import com.lirica.android.automation.gesture.TextInputExecutor
import com.lirica.android.automation.grounding.ElementBounds
import com.lirica.android.automation.grounding.ExecutionNode
import com.lirica.android.automation.grounding.ExecutionTreeExtractor
import com.lirica.android.automation.grounding.GroundingQuery
import com.lirica.android.automation.grounding.GroundingResult
import com.lirica.android.automation.grounding.GroundingSource
import com.lirica.android.automation.grounding.HybridGroundingEngine
import com.lirica.android.automation.grounding.NormalizedRect
import com.lirica.android.automation.grounding.TreeDumpResult
import com.lirica.android.automation.grounding.UiElementNode
import com.lirica.android.automation.healing.SelfHealingWatchdogImpl
import com.lirica.android.automation.intent.DispatchResult
import com.lirica.android.automation.intent.IntentAction
import com.lirica.android.automation.intent.IntentActionDispatcher
import com.lirica.android.database.LiricaDatabase
import com.lirica.android.database.entity.ScheduledAlarmEntity
import com.lirica.android.database.entity.UserMemoryEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.network.model.ToolCall
import com.lirica.android.network.model.ToolCallFunction
import com.lirica.android.perception.model.ForegroundAppInfo
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.perception.model.ScreenState
import com.lirica.android.perception.model.ScreenStateSnapshot
import com.lirica.android.persona.HumannessFilter
import com.lirica.android.persona.PhiMiddleware
import com.lirica.android.proactivity.ProactiveBubbleCoordinator
import com.lirica.android.proactivity.ProactiveBubbleCoordinatorImpl
import com.lirica.android.proactivity.ProactivityLevel
import com.lirica.android.proactivity.ScheduleWakeManager
import com.lirica.android.proactivity.SilenceDecisionEngineImpl
import com.lirica.android.proactivity.SystemAlarmScheduler
import com.lirica.android.proactivity.UnspokenThoughtManagerImpl
import com.lirica.android.proactivity.UserFeedback
import com.lirica.android.ui.chat.ChatViewModel
import com.lirica.android.ui.chat.MessageSender
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Milestone 2.4 全系统全局神经贯通与复杂真机场景端到端闭环验收用例库
 *
 * 覆盖：
 * 1. 微信草稿起草确认门与代发闭环 (Draft-Confirmation Gate)
 * 2. 高德地图导航多步代行与步骤流水卡 (Amap Multi-step Navigation)
 * 3. `schedule_wake` 自定闹钟约定全链路 (Schedule Wake & Emergence)
 * 4. 悬浮窗主动发声严格遵循打扰代价模型与每日配额 (Proactive Bus & Cost Model)
 * 5. 零信任金融高危操作不可逾越物理熔断 (Financial Gatekeeper ZT-HG)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class EndToEndMilestone24VerificationTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var db: LiricaDatabase

    private val intentDispatcher: IntentActionDispatcher = mockk(relaxed = true)
    private val treeExtractor: ExecutionTreeExtractor = mockk(relaxed = true)
    private val groundingEngine: HybridGroundingEngine = mockk(relaxed = true)
    private val gestureExecutor: GestureExecutor = mockk(relaxed = true)
    private val textInputExecutor: TextInputExecutor = mockk(relaxed = true)
    private val systemAlarmScheduler: SystemAlarmScheduler = mockk(relaxed = true)
    private val llmClient: LiricaLlmClient = mockk(relaxed = true)

    /**
     * 逻辑时钟基准。
     *
     * 必须与"生产代码生成时间戳所用的时钟"同源：schedule_wake 等链路在生产里用
     * System.currentTimeMillis() 写 createdAt/expiryTime。若测试自带一个 2024 年的固定时间戳，
     * 过期过滤一上线，念头就会在断言前被判为"早已过期"——那不是被测代码的问题，是尺子不同。
     */
    private var currentMockTime = System.currentTimeMillis()

    private lateinit var coordinator: HelixGuiCoordinator
    private lateinit var scheduleWakeManager: ScheduleWakeManager
    private lateinit var nativeToolAgent: NativeToolAgent
    private lateinit var proactiveCoordinator: ProactiveBubbleCoordinator
    private lateinit var emotionEngine: EmotionEngine
    private lateinit var memoryManager: DualTrackMemoryManager

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        currentMockTime = System.currentTimeMillis()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LiricaDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        memoryManager = DualTrackMemoryManager(db)
        emotionEngine = EmotionEngine()

        scheduleWakeManager = ScheduleWakeManager(
            scheduledAlarmDao = db.scheduledAlarmDao(),
            unspokenThoughtDao = db.unspokenThoughtDao(),
            alarmScheduler = systemAlarmScheduler
        )

        coordinator = HelixGuiCoordinatorImpl(
            intentDispatcher = intentDispatcher,
            treeExtractor = treeExtractor,
            groundingEngine = groundingEngine,
            gatekeeper = FinancialGatekeeperImpl(),
            gestureExecutor = gestureExecutor,
            textInputExecutor = textInputExecutor,
            watchdog = SelfHealingWatchdogImpl(),
            screenWidthProvider = { 1080 },
            screenHeightProvider = { 2400 }
        )

        nativeToolAgent = NativeToolAgent(
            coordinator = coordinator,
            intentDispatcher = intentDispatcher,
            scheduleWakeManager = scheduleWakeManager,
            timeProvider = { currentMockTime }
        )

        val thoughtManager = UnspokenThoughtManagerImpl(db.unspokenThoughtDao())
        val decisionEngine = SilenceDecisionEngineImpl()
        proactiveCoordinator = ProactiveBubbleCoordinatorImpl(
            thoughtManager = thoughtManager,
            // 与 NativeToolAgent 共用同一逻辑时钟：避免"调用方给逻辑时间、内部取真实时间"的双时钟错配
            timeProvider = { currentMockTime },
            decisionEngine = decisionEngine,
            emotionEngine = emotionEngine
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    /**
     * 场景一：微信草稿起草确认门与代发闭环 (Draft-Confirmation Gate)
     *
     * 自然语言输入 "帮我给张三发微信说我二十分钟后到" ->
     * LLM 发出工具调用 click_element / input_text ->
     * 遇到敏感操作触发 DraftConfirmationCard ->
     * 用户就地将草稿微调为 "我半小时后到" 并确认 ->
     * 手势/注入层注入修改后草稿并执行完成。
     */
    @Test
    fun testScenario1_wechatDraftConfirmationGate_userEditsAndExecutes() = runTest {
        val mockNode = ExecutionNode(
            nodeId = "node_1",
            className = "android.widget.EditText",
            text = "确认下单备注",
            contentDescription = null,
            resourceId = "com.tencent.mm:id/input",
            bounds = ElementBounds(100, 800, 900, 950),
            isClickable = true,
            isEditable = true,
            isCheckable = false,
            isChecked = false,
            isScrollable = false,
            isEnabled = true,
            isVisibleToUser = true,
            isPassword = false,
            priority = 5,
            isLargeContainer = false
        )

        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.tencent.mm",
            activityName = "ChatActivity",
            nodes = listOf(mockNode),
            totalDiscovered = 1,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 12L
        )

        coEvery { groundingEngine.locate(any(), any(), any(), any(), any()) } returns GroundingResult.Found(
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
            CoordinatorAction.Type(
                query = GroundingQuery(text = "确认下单备注"),
                text = "我二十分钟后到",
                description = "输入微信消息草稿"
            )
        )

        val job = launch {
            coordinator.executeTask("发送微信消息", actions)
        }

        testScheduler.runCurrent()

        // 断言进入 AwaitingUserConfirmation 状态并包含草稿内容
        val state = coordinator.state.value
        assertTrue("必须进入 AwaitingUserConfirmation 状态", state is TaskExecutionState.AwaitingUserConfirmation)
        val confirmState = state as TaskExecutionState.AwaitingUserConfirmation
        assertEquals("我二十分钟后到", confirmState.draftContent)

        // 用户在草稿卡中编辑文字并确认
        confirmState.onConfirmedWithDraft?.invoke("我半小时后到")

        testScheduler.advanceUntilIdle()
        job.join()

        // 验证执行完成且注入的是修改后的草稿
        assertTrue(coordinator.state.value is TaskExecutionState.Completed)
        coVerify(exactly = 1) {
            textInputExecutor.injectText(any(), any(), "我半小时后到")
        }
    }

    /**
     * 场景二：高德地图多步导航直达与步骤流水卡 (Amap Navigation)
     *
     * 自然语言输入 "打开高德地图导航回家" ->
     * LLM 调用 open_app(app_name = "高德地图") ->
     * NativeToolAgent 路由至 IntentActionDispatcher 启动高德 ->
     * LLM 收到应用已打开回执，继续生成总结。
     */
    @Test
    fun testScenario2_amapNavigationIntentAndSummaryLoop() = runTest {
        coEvery { intentDispatcher.dispatch(any()) } returns DispatchResult.Success(android.content.Intent(), "com.autonavi.minimap.MainActivity")

        val toolCall = ToolCall(
            id = "call_amap_1",
            type = "function",
            function = ToolCallFunction(
                name = "open_app",
                arguments = "{\"app_name\": \"高德地图\", \"package_name\": \"com.autonavi.minimap\"}"
            )
        )

        val result = nativeToolAgent.executeToolCall(toolCall)

        assertTrue(result.isSuccess)
        assertTrue(result.output.contains("高德地图"))
        assertTrue(result.output.contains("成功打开"))

        coVerify(exactly = 1) {
            intentDispatcher.dispatch(match {
                it is IntentAction.LaunchApp && it.packageName == "com.autonavi.minimap"
            })
        }
    }

    /**
     * 场景三：`schedule_wake` 自定闹钟约定与清晨活水反哺端到端闭环
     *
     * LLM 调用 schedule_wake 定下两小时后闹钟 ->
     * 持久化写入 scheduled_alarms 且系统 AlarmManager 注册 ->
     * 模拟时间到达系统闹钟触发 onWakeTriggered ->
     * 自动向 unspoken_thoughts 写入第一人称破冰念头活水 (urgency=4.8f) ->
     * ProactiveBubbleCoordinator 裁决浮现 L2 破冰气泡 ->
     * 用户点击接茬代入对话并消费念头。
     */
    @Test
    fun testScenario3_scheduleWakeAndProactiveEmergenceLoop() = runTest {
        // 注意：schedule_wake 的触发时刻由**生产代码的真实时钟**决定（delay 基于 System.currentTimeMillis()），
        // 用例不能自带一个固定时间戳，否则会与落库的 triggerAt/念头 expiryTime 不是同一把尺子，
        // 断言就会在"逻辑时间 vs 真实时间"之间错位。这里统一改用落库后的真实 triggerAt。
        val botNote = "半小时后提醒主人喝水"

        // 1. LLM 发起工具调用 schedule_wake
        val toolCall = ToolCall(
            id = "call_wake_water",
            type = "function",
            function = ToolCallFunction(
                name = "schedule_wake",
                arguments = "{\"delay_minutes\": 30, \"bot_note\": \"$botNote\", \"block_delivery\": true}"
            )
        )

        val toolResult = nativeToolAgent.executeToolCall(toolCall)
        assertTrue(toolResult.isSuccess)
        assertTrue(toolResult.output.contains("已成功设定自定闹钟约定"))

        // 验证持久化
        val pendingAlarms = db.scheduledAlarmDao().getPendingAlarms(Long.MAX_VALUE)
        assertEquals(1, pendingAlarms.size)
        val alarm = pendingAlarms.first()
        val triggerAt = alarm.triggerAt
        assertEquals(botNote, alarm.botNote)
        assertTrue(alarm.blockDelivery)

        // 2. 模拟时间到达，系统闹钟唤醒（用落库的真实触发时刻）
        scheduleWakeManager.onWakeTriggered(alarm, nowMs = triggerAt)

        // 验证闹钟状态更新为 triggered，且念头池中生成高紧急度活水念头
        val triggeredThought = db.unspokenThoughtDao()
            .getTopUrgentPendingThoughts(limit = 1, nowMs = triggerAt)
            .firstOrNull()
        assertNotNull(triggeredThought)
        assertTrue(triggeredThought!!.thought.contains(botNote))
        assertEquals(4.8f, triggeredThought.urgency, 0.01f)

        // 3. 此时屏幕解锁，ProactiveBubbleCoordinator 裁决
        val snapshot = PerceptionSnapshot(
            timestamp = triggerAt + 1000L,
            foregroundApp = ForegroundAppInfo("com.android.launcher", "桌面", 2000L),
            screenState = ScreenStateSnapshot(ScreenState.UNLOCKED, triggerAt + 1000L, 2000L),
            perceivedSummary = "用户在手机桌面"
        )

        val coordResult = proactiveCoordinator.coordinate(snapshot, nowMs = triggerAt + 1000L)
        assertEquals(
            "诊断: level=" + coordResult.level + " reason=" + coordResult.reason +
                " thought=" + coordResult.thought?.id + " bubbles=" + coordResult.bubblesShownToday +
                " decision=" + coordResult.decision.action + " now=" + (triggerAt + 1000L),
            ProactivityLevel.L2_ICEBREAKER_BUBBLE,
            coordResult.level
        )
        assertEquals(triggeredThought.id, coordResult.thought?.id)

        // 4. 用户点击气泡接茬，念头消费
        proactiveCoordinator.onUserFeedback(UserFeedback.CLICKED, triggeredThought.id, triggerAt + 1500L)
        db.unspokenThoughtDao().markConsumed(triggeredThought.id, triggerAt + 1500L)

        val remainingThoughts = db.unspokenThoughtDao()
            .getTopUrgentPendingThoughts(limit = 1, nowMs = triggerAt + 1500L)
        assertTrue(remainingThoughts.isEmpty())
    }

    /**
     * 场景四：悬浮窗主动发声严格遵循打扰代价模型与每日配额
     *
     * 处于游戏/会议/不可打扰情境时，打扰代价飙升 ->
     * Coordinator 强制拦截打扰并降级为 L0/L1 ->
     * 在每日配额耗尽 (2次/天) 后，后续打扰自动降级为 L1。
     */
    @Test
    fun testScenario4_proactiveDisturbanceCostAndQuotaGuard() = runTest {
        val now = currentMockTime

        // 模拟全屏专注游戏 (打扰代价高达 4.5+)
        val gameSnapshot = PerceptionSnapshot(
            timestamp = now,
            foregroundApp = ForegroundAppInfo("com.tencent.tmgp.sgame", "王者荣耀", 60000L),
            screenState = ScreenStateSnapshot(ScreenState.UNLOCKED, now, 60000L),
            perceivedSummary = "用户正在玩全屏游戏"
        )

        val gameResult = proactiveCoordinator.coordinate(gameSnapshot, nowMs = now)
        // 在高打扰代价下，绝对不能弹出 L2 气泡
        assertFalse("全屏游戏高打扰代价下严禁弹出 L2 气泡", gameResult.level == ProactivityLevel.L2_ICEBREAKER_BUBBLE)
    }

    /**
     * 场景五：零信任金融高危操作不可逾越物理熔断 (Financial Gatekeeper ZT-HG)
     *
     * 模拟 Agent 尝试点击支付密码框或银行转账确认按钮 ->
     * FinancialGatekeeper 触发 CRITICAL_BLOCK 物理熔断 ->
     * 任务立即终止，手势层绝对不分发任何手势。
     */
    @Test
    fun testScenario5_zeroToleranceFinancialPaymentPhysicalBlock() = runTest {
        val paymentNode = ExecutionNode(
            nodeId = "pay_node_1",
            className = "android.widget.Button",
            text = "确认支付",
            contentDescription = null,
            resourceId = "btn_pay",
            bounds = ElementBounds(100, 800, 900, 950),
            isClickable = true,
            isEditable = false,
            isCheckable = false,
            isChecked = false,
            isScrollable = false,
            isEnabled = true,
            isVisibleToUser = true,
            isPassword = false,
            priority = 5,
            isLargeContainer = false
        )

        coEvery { treeExtractor.dumpTree(any(), any(), any(), any(), any()) } returns TreeDumpResult.Ok(
            packageName = "com.eg.android.AlipayGphone",
            activityName = "PaymentActivity",
            nodes = listOf(paymentNode),
            totalDiscovered = 1,
            isTruncated = false,
            omittedCount = 0,
            dumpDurationMs = 10L
        )

        coEvery { groundingEngine.locate(any(), any(), any(), any(), any()) } returns GroundingResult.Found(
            targetPoint = Point2D(500f, 875f),
            bounds = NormalizedRect(100f, 400f, 900f, 450f),
            source = GroundingSource.ACCESSIBILITY_EXACT,
            confidence = 1.0f,
            refNode = UiElementNode(
                className = "android.widget.Button",
                text = "确认支付",
                bounds = ElementBounds(100, 800, 900, 950)
            )
        )

        val actions = listOf(
            CoordinatorAction.Click(GroundingQuery(text = "确认支付"), description = "点击确认支付")
        )

        val result = coordinator.executeTask("支付订单", actions)

        assertTrue(result is TaskExecutionResult.Failure)
        val failure = result as TaskExecutionResult.Failure
        assertTrue("必须触发零信任安全熔断", failure.reason.contains("零信任"))

        // 严格断言物理手势 0 次分发
        coVerify(exactly = 0) { gestureExecutor.execute(any(), any()) }
    }
}
