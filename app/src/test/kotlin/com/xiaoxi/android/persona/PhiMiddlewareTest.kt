package com.xiaoxi.android.persona

import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.network.model.ChatCompletionRequest
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.persona.model.CharacterCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhiMiddlewareTest {

    private lateinit var driftShieldScheduler: DriftShieldScheduler
    private lateinit var phiMiddleware: PhiMiddleware

    @Before
    fun setUp() {
        driftShieldScheduler = DriftShieldScheduler(interval = 8)
        phiMiddleware = PhiMiddleware(driftShieldScheduler)
    }

    @Test
    fun testContextAssemblySequenceOrder() {
        val systemPrompt = "基础人设：你是小汐，来自映海的鲸族少女。"
        val dynamicContext = "【情境与记忆】当前时间：14:00，用户记忆：喜欢喝冰乌龙茶。"
        val history = listOf(
            ChatMessage(role = "user", content = "今天好热，喝点什么好？")
        )

        val assembled = phiMiddleware.assemble(
            systemPrompt = systemPrompt,
            dynamicContext = dynamicContext,
            historyMessages = history,
            moodState = MoodState.HAPPY
        )

        // 验证装配流顺序：[System Prompt / 角色卡基础设定] -> [动态环境与记忆槽] -> [对话历史] -> [Post-History Instructions (PHI)]
        assertEquals(4, assembled.size)

        assertEquals("system", assembled[0].role)
        assertEquals(systemPrompt, assembled[0].content)

        assertEquals("system", assembled[1].role)
        assertEquals(dynamicContext, assembled[1].content)

        assertEquals("user", assembled[2].role)
        assertEquals("今天好热，喝点什么好？", assembled[2].content)

        assertEquals("system", assembled[3].role)
        val phiContent = assembled[3].content.orEmpty()
        assertTrue("PHI 必须包含语气铁律", phiContent.contains("语气铁律"))
        assertTrue("PHI 必须包含即时情绪文风", phiContent.contains(MoodState.HAPPY.styleHint))

        // 断言尾部 PHI 指令在 Prompt 序列中的绝对位置（必须紧跟在用户最新输入之后）
        assertEquals(assembled.last(), assembled[3])
        assertEquals(assembled[assembled.size - 2], history.last())
    }

    @Test
    fun testTailPhiRecencyBiasAndAntiCustomerServiceTone() {
        val history = listOf(
            ChatMessage(role = "user", content = "你觉得我该怎么选？")
        )

        val assembled = phiMiddleware.assemble(
            systemPrompt = "你是小汐",
            historyMessages = history
        )

        val tailMessage = assembled.last()
        assertEquals("system", tailMessage.role)
        val tailContent = tailMessage.content.orEmpty()

        // 验证反客服腔与真人语气铁律
        assertTrue("必须包含反客服腔约束", tailContent.contains("不用客服腔"))
        assertTrue("必须包含短句利落要求", tailContent.contains("短句利落"))
        assertTrue("必须包含动作括号限制", tailContent.contains("动作括号至多1个"))
        assertTrue("必须严禁破折号", tailContent.contains("不用破折号"))
    }

    @Test
    fun testMoodStateStyleHintIntegration() {
        val history = listOf(ChatMessage(role = "user", content = "你在干嘛呢？"))

        // 测试兴奋情绪文风注入
        val assembledExcited = phiMiddleware.assemble(
            historyMessages = history,
            moodState = MoodState.EXCITED
        )
        val excitedPhi = assembledExcited.last().content.orEmpty()
        assertTrue(excitedPhi.contains(MoodState.EXCITED.styleHint))

        // 测试闹别扭情绪文风注入
        val assembledSulking = phiMiddleware.assemble(
            historyMessages = history,
            moodState = MoodState.SULKING
        )
        val sulkingPhi = assembledSulking.last().content.orEmpty()
        assertTrue(sulkingPhi.contains(MoodState.SULKING.styleHint))

        // 测试不传 moodState 时优雅降级（不注入心情行但保留语气铁律）
        val assembledNullMood = phiMiddleware.assemble(
            historyMessages = history,
            moodState = null
        )
        val nullMoodPhi = assembledNullMood.last().content.orEmpty()
        assertTrue(nullMoodPhi.contains("语气铁律"))
        assertFalse(nullMoodPhi.contains("此刻状态与文风"))
    }

    @Test
    fun testDriftShieldPeriodicTriggerBoundaries() {
        val scheduler = DriftShieldScheduler(interval = 8)

        // 轮次 0 不应触发
        assertFalse(scheduler.shouldTrigger(0))
        assertFalse(scheduler.shouldTrigger(-1))

        // 轮次 1..7 未触发
        for (turn in 1..7) {
            assertFalse("第 $turn 轮不应触发抗漂移", scheduler.shouldTrigger(turn))
        }

        // 第 8 轮精准触发
        assertTrue("第 8 轮必须精准触发抗漂移", scheduler.shouldTrigger(8))

        // 轮次 9..15 未触发
        for (turn in 9..15) {
            assertFalse("第 $turn 轮不应触发抗漂移", scheduler.shouldTrigger(turn))
        }

        // 第 16 轮精准触发
        assertTrue("第 16 轮必须精准触发抗漂移", scheduler.shouldTrigger(16))

        // 第 24 轮精准触发
        assertTrue("第 24 轮必须精准触发抗漂移", scheduler.shouldTrigger(24))
    }

    @Test
    fun testDriftShieldSessionIsolationAndTurnTracking() {
        val scheduler = DriftShieldScheduler(interval = 8)
        val sessionA = "session-test-alpha"
        val sessionB = "session-test-beta"

        // Session A 走 7 轮
        for (i in 1..7) {
            val turn = scheduler.recordTurn(sessionA)
            assertEquals(i, turn)
            assertFalse(scheduler.shouldTriggerForSession(sessionA))
        }

        // Session B 走 2 轮，相互独立不干扰
        assertEquals(1, scheduler.recordTurn(sessionB))
        assertEquals(2, scheduler.recordTurn(sessionB))
        assertEquals(2, scheduler.getTurnCount(sessionB))
        assertEquals(7, scheduler.getTurnCount(sessionA))

        // Session A 第 8 轮：触发
        val turnA8 = scheduler.recordTurn(sessionA)
        assertEquals(8, turnA8)
        assertTrue(scheduler.shouldTriggerForSession(sessionA))
        assertFalse(scheduler.shouldTriggerForSession(sessionB))

        // 重置 Session A
        scheduler.reset(sessionA)
        assertEquals(0, scheduler.getTurnCount(sessionA))
        assertEquals(2, scheduler.getTurnCount(sessionB))
    }

    @Test
    fun testConcurrentTurnTrackingThreadSafety() = runBlocking {
        val scheduler = DriftShieldScheduler(interval = 8)
        val sessionId = "concurrent-session"

        val jobs = (1..100).map {
            async(Dispatchers.Default) {
                scheduler.recordTurn(sessionId)
            }
        }
        jobs.awaitAll()

        assertEquals(100, scheduler.getTurnCount(sessionId))
    }

    @Test
    fun testPhiAssemblyWithDriftShieldTriggeredOnTurn8And16() {
        val history = listOf(ChatMessage(role = "user", content = "今天也是元气满满的一天！"))

        // 第 7 轮：未触发抗漂移护盾
        val assembledTurn7 = phiMiddleware.assemble(
            historyMessages = history,
            turnCount = 7,
            moodState = MoodState.LIVELY
        )
        val phi7 = assembledTurn7.last().content.orEmpty()
        assertFalse("第 7 轮不应包含抗漂移护盾", phi7.contains("核心人格重锚（抗漂移护盾）"))
        assertTrue("第 7 轮必须保留语气铁律", phi7.contains("语气铁律"))

        // 第 8 轮：必须注入抗漂移护盾
        val assembledTurn8 = phiMiddleware.assemble(
            historyMessages = history,
            turnCount = 8,
            moodState = MoodState.LIVELY
        )
        val phi8 = assembledTurn8.last().content.orEmpty()
        assertTrue("第 8 轮必须注入抗漂移护盾", phi8.contains("核心人格重锚（抗漂移护盾）"))
        assertTrue("第 8 轮必须同时包含语气铁律", phi8.contains("语气铁律"))
        assertTrue("第 8 轮必须同时包含情绪文风", phi8.contains(MoodState.LIVELY.styleHint))
        assertEquals("PHI 必须仍在消息最尾端", assembledTurn8.last(), assembledTurn8[assembledTurn8.size - 1])

        // 第 15 轮：未触发
        val assembledTurn15 = phiMiddleware.assemble(
            historyMessages = history,
            turnCount = 15
        )
        assertFalse(assembledTurn15.last().content.orEmpty().contains("核心人格重锚（抗漂移护盾）"))

        // 第 16 轮：必须注入
        val assembledTurn16 = phiMiddleware.assemble(
            historyMessages = history,
            turnCount = 16
        )
        assertTrue(assembledTurn16.last().content.orEmpty().contains("核心人格重锚（抗漂移护盾）"))
    }

    @Test
    fun testCharacterCardIntegrationWithMacroExpansion() {
        val card = CharacterCard(
            name = "小汐",
            systemPrompt = "你是{{char}}，陪伴在{{user}}身边的伴生智能体。",
            postHistoryInstructions = "{{char}}务必记住：对{{user}}保持真诚，绝不敷衍。"
        )
        val history = listOf(ChatMessage(role = "user", content = "你好呀"))

        val assembled = phiMiddleware.assemble(
            characterCard = card,
            historyMessages = history,
            userName = "小月",
            charName = "小汐"
        )

        // 系统主提示词宏展开验证
        val systemMessage = assembled.first()
        assertEquals("system", systemMessage.role)
        assertTrue(systemMessage.content.orEmpty().contains("你是小汐，陪伴在小月身边的伴生智能体。"))

        // 尾部 PHI 宏展开与卡片指令注入验证
        val phiMessage = assembled.last()
        assertEquals("system", phiMessage.role)
        val phiContent = phiMessage.content.orEmpty()
        assertTrue(phiContent.contains("小汐务必记住：对小月保持真诚，绝不敷衍。"))
        assertTrue(phiContent.contains("语气铁律"))
    }

    @Test
    fun testProcessChatCompletionRequest() {
        val request = ChatCompletionRequest(
            model = "deepseek-chat",
            messages = listOf(
                ChatMessage(role = "system", content = "你是小汐"),
                ChatMessage(role = "user", content = "写一首短诗")
            ),
            temperature = 0.95
        )

        val processed = phiMiddleware.processRequest(
            request = request,
            moodState = MoodState.CALM,
            turnCount = 8
        )

        assertEquals("deepseek-chat", processed.model)
        assertEquals(0.95, processed.temperature)
        assertEquals(3, processed.messages.size)
        assertEquals("user", processed.messages[1].role)
        assertEquals("system", processed.messages[2].role)

        val tailContent = processed.messages[2].content.orEmpty()
        assertTrue(tailContent.contains("语气铁律"))
        assertTrue(tailContent.contains(MoodState.CALM.styleHint))
        assertTrue(tailContent.contains("核心人格重锚（抗漂移护盾）"))
    }

    @Test
    fun testOmissionOfEmptyDynamicContext() {
        val history = listOf(ChatMessage(role = "user", content = "测试消息"))

        // dynamicContext 为 null
        val assembled1 = phiMiddleware.assemble(
            systemPrompt = "你是小汐",
            dynamicContext = null,
            historyMessages = history
        )
        // 只有 systemPrompt, user history, tail PHI (3 条)
        assertEquals(3, assembled1.size)
        assertEquals("system", assembled1[0].role)
        assertEquals("user", assembled1[1].role)
        assertEquals("system", assembled1[2].role)

        // dynamicContext 为空白字符串
        val assembled2 = phiMiddleware.assemble(
            systemPrompt = "你是小汐",
            dynamicContext = "   \n  ",
            historyMessages = history
        )
        assertEquals(3, assembled2.size)
    }

    @Test
    fun testCustomCharacterCardAdaptiveDriftShield() {
        // 自定义角色卡：福尔摩斯
        val sherlockCard = CharacterCard(
            name = "夏洛克·福尔摩斯",
            personality = "敏锐毒舌、极度理性的咨询侦探",
            description = "贝克街221B的住客"
        )
        val history = listOf(ChatMessage(role = "user", content = "华生去哪了？"))

        val assembled = phiMiddleware.assemble(
            characterCard = sherlockCard,
            historyMessages = history,
            turnCount = 8
        )

        val tailContent = assembled.last().content.orEmpty()
        // 关键断言：自定义角色卡在第 8 轮触发抗漂移时，绝不能被注入硬编码的“映海来的鲸族少女小汐”！
        assertFalse("自定义角色卡严禁被小汐人设污染", tailContent.contains("映海来的鲸族少女"))
        assertTrue("必须包含该角色的姓名重锚", tailContent.contains("夏洛克·福尔摩斯"))
        assertTrue("必须包含该角色的核心性格重锚", tailContent.contains("敏锐毒舌、极度理性的咨询侦探"))
    }

    @Test
    fun testCharacterBookLorebookIntegration() {
        val book = com.xiaoxi.android.persona.model.CharacterBook(
            name = "贝克街档案",
            entries = listOf(
                com.xiaoxi.android.persona.model.CharacterBookEntry(
                    keys = listOf("烟斗", "烟草"),
                    content = "夏洛克在思考难题时喜欢点燃石楠根烟斗",
                    insertionOrder = 10
                )
            )
        )
        val card = CharacterCard(
            name = "夏洛克",
            characterBook = book
        )
        val history = listOf(ChatMessage(role = "user", content = "先生，你的烟斗放在桌上了。"))

        val assembled = phiMiddleware.assemble(
            characterCard = card,
            historyMessages = history
        )

        // 验证世界书条目被成功激活并注入到上下文中的阶段 2 动态环境槽
        assertEquals(4, assembled.size)
        val contextMessage = assembled[1]
        assertEquals("system", contextMessage.role)
        assertTrue(contextMessage.content.orEmpty().contains("## 贝克街档案"))
        assertTrue(contextMessage.content.orEmpty().contains("夏洛克在思考难题时喜欢点燃石楠根烟斗"))
    }
}
