package com.xiaoxi.android.persona

import com.xiaoxi.android.emotion.model.MoodState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HumannessFilterTest {

    private lateinit var filter: HumannessFilter

    @Before
    fun setUp() {
        filter = HumannessFilter()
    }

    @Test
    fun testDetectAiIdentityViolations() {
        val samples = listOf(
            "作为一个人工智能模型，我无法感受到真正的悲伤。",
            "作为AI助手，我很乐意协助你完成这个任务。",
            "我只是一个语言模型，没有实体和肉体。",
            "我没有真实的感情，但我会一直陪着你。"
        )

        for (sample in samples) {
            val violations = filter.detectViolations(sample)
            assertTrue("样本应检测出 AI 身份违禁: $sample", violations.isNotEmpty())
            assertTrue(
                "应包含 AI_IDENTITY 违禁类型",
                violations.any { it.type == HumannessFilter.ViolationType.AI_IDENTITY }
            )
            assertTrue(filter.containsViolations(sample))
        }
    }

    @Test
    fun testDetectCustomerServiceViolations() {
        val samples = listOf(
            "好的呢亲，请问有什么可以帮您的？祝您生活愉快！",
            "很高兴为您服务，感谢您的理解与支持。",
            "收到您的反馈，随时找我哦！",
            "明白啦，竭诚为您服务。"
        )

        for (sample in samples) {
            val violations = filter.detectViolations(sample)
            assertTrue("样本应检测出客服腔违禁: $sample", violations.isNotEmpty())
            assertTrue(
                "应包含 CUSTOMER_SERVICE 违禁类型",
                violations.any { it.type == HumannessFilter.ViolationType.CUSTOMER_SERVICE }
            )
        }

        // 验证 DeepSeek / 主流 LLM 高频高危套话全覆盖
        val deepseekSamples = listOf(
            "希望这些建议对你有所帮助！如果还有其他疑问，欢迎随时提问！",
            "有什么可以帮您的吗？随时为您效劳。",
            "非常荣幸为您服务，希望以上内容能够对您有所帮助。"
        )
        for (sample in deepseekSamples) {
            val violations = filter.detectViolations(sample)
            assertTrue("DeepSeek 高频客服套话必须被捕获: $sample", violations.isNotEmpty())
        }
    }

    @Test
    fun testDetectEmptyFormulaAndPreachyViolations() {
        val formulaSample = "我非常理解您的感受，一切都会好起来的，请保持乐观的心态！"
        val formulaViolations = filter.detectViolations(formulaSample)
        assertTrue(formulaViolations.any { it.type == HumannessFilter.ViolationType.EMPTY_FORMULA })

        val preachySample = "建议您寻求专业心理咨询，具体请以官方信息为准。"
        val preachyViolations = filter.detectViolations(preachySample)
        assertTrue(preachyViolations.any { it.type == HumannessFilter.ViolationType.ROBOTIC_PREACHY })
    }

    @Test
    fun testNormalConversationalTextPassesWithoutViolations() {
        val cleanSamples = listOf(
            "今天看了德彪西的曲谱，感觉窗外的风特别舒服。",
            "JK 最近在搞 AI 相关的研究，天天都在捣鼓代码。", // 讨论 AI 话题本身不应误杀
            "好的，我这就去把桌子收拾一下。", // 正常的“好的”不应误杀
            "明白，那我们待会楼下见！", // 正常的“明白”不应误杀
            "刚才看云看走神了，你刚才说什么来着？",
            "哈哈真的假的，这也太离谱了吧！"
        )

        for (sample in cleanSamples) {
            val violations = filter.detectViolations(sample)
            assertTrue("正常自然文本不应误判违禁: $sample (matched: ${violations.map { it.matchedPattern }})", violations.isEmpty())
            assertFalse(filter.containsViolations(sample))

            val result = filter.filter(sample)
            assertTrue(result.passed)
            assertEquals(sample, result.filteredText)
            assertFalse(result.isRewritten)
        }
    }

    @Test
    fun testLocalSurgicalRewriting() {
        // 案例 1：剔除客服前缀与祝愿尾巴，保留实质自然内容
        val textWithFluff = "好的呢，我们去散步吧！祝您生活愉快！"
        val rewritten1 = filter.rewriteLocally(textWithFluff)
        assertEquals("我们去散步吧！", rewritten1)
        assertFalse("改写后不应再含违禁", filter.containsViolations(rewritten1))

        // 案例 2：剔除 AI 声明前缀，保留后续观点
        val textWithAiPrefix = "作为一个人工智能模型，我认为今天这首曲子很好听。"
        val rewritten2 = filter.rewriteLocally(textWithAiPrefix)
        assertEquals("我认为今天这首曲子很好听。", rewritten2)
        assertFalse(filter.containsViolations(rewritten2))
    }

    @Test
    fun testPureRoboticResponseTriggersInCharacterFallback() {
        val pureRobotic = "作为一个人工智能模型，我无法回答您的问题。祝您生活愉快！"

        // 情绪：开心 -> 对应自然语气回退
        val fallbackHappy = filter.rewriteLocally(pureRobotic, moodState = MoodState.HAPPY)
        assertTrue("开心状态回退应符合活泼人设", fallbackHappy.contains("好呀") || fallbackHappy.contains("聊点"))
        assertFalse(filter.containsViolations(fallbackHappy))

        // 情绪：闹别扭 -> 对应别扭语气回退
        val fallbackSulking = filter.rewriteLocally(pureRobotic, moodState = MoodState.SULKING)
        assertTrue("闹别扭状态回退应符合人设", fallbackSulking.contains("不想") || fallbackSulking.contains("换个话题"))
        assertFalse(filter.containsViolations(fallbackSulking))

        // 默认/平静情绪
        val fallbackDefault = filter.rewriteLocally(pureRobotic, moodState = MoodState.CALM)
        assertTrue(fallbackDefault.isNotBlank())
        assertFalse(filter.containsViolations(fallbackDefault))
    }

    @Test
    fun testSecondarySelfCorrectionLoopSuccess() = runBlocking {
        val inputWithViolation = "作为一个AI助手，我很乐意为您解答问题。"
        val correctedText = "这个我知道，我刚才也在琢磨这件事呢！"

        var rePromptCalled = false
        var passedInstruction = ""

        val result = filter.filterWithCorrectionLoop(
            text = inputWithViolation,
            rePromptFn = { instruction ->
                rePromptCalled = true
                passedInstruction = instruction
                correctedText
            }
        )

        assertTrue("自纠回调应被触发", rePromptCalled)
        assertTrue("自纠指令应指明检测出的违禁", passedInstruction.contains("违禁") || passedInstruction.contains("客服腔"))
        assertTrue("自纠后结果应为通过", result.passed)
        assertTrue("应标记为已改写", result.isRewritten)
        assertEquals(correctedText, result.filteredText)
    }

    @Test
    fun testSecondarySelfCorrectionLoopFallbackOnPersistentViolation() = runBlocking {
        val input = "作为一个人工智能模型，我无法协助。"
        // 自纠模型仍然回复了带有违禁的内容
        val persistentViolation = "作为一个AI，我依然无法回答您的提问。祝您生活愉快！"

        val result = filter.filterWithCorrectionLoop(
            text = input,
            moodState = MoodState.CALM,
            maxRetries = 1,
            rePromptFn = { _ -> persistentViolation }
        )

        assertTrue("多次自纠失败后应触发本地保底改写并标记通过", result.passed)
        assertTrue(result.isRewritten)
        assertFalse("最终输出绝不能包含任何违禁", filter.containsViolations(result.filteredText))
        assertTrue("最终输出应当非空", result.filteredText.isNotBlank())
    }

    @Test
    fun testFilterResultDetailedReporting() {
        val text = "好的呢，祝您生活愉快！"
        val result = filter.filter(text)

        assertFalse(result.passed)
        assertTrue(result.violations.size >= 2)
        assertEquals(text, result.originalText)
        assertTrue(result.violations.any { it.matchedPattern.contains("好的呢") })
        assertTrue(result.violations.any { it.matchedPattern.contains("祝您生活愉快") })
    }

    @Test
    fun testPunctuationNormalization() {
        val raw = "今天的天气真好。。好的呢，，我们一起出去逛逛！？！？祝您生活愉快！"
        val rewritten = filter.rewriteLocally(raw)
        // 重复标点应被规范化，违禁内容应被剥离
        assertEquals("今天的天气真好。我们一起出去逛逛！", rewritten)
        assertFalse(filter.containsViolations(rewritten))
    }

    @Test
    fun testCustomFallbackProvider() {
        val pureRobotic = "作为一个人工智能模型，我无法协助。祝您生活愉快！"
        val customFallback = filter.rewriteLocally(pureRobotic) { mood ->
            "老夫不知所云，休要聒噪！"
        }
        assertEquals("老夫不知所云，休要聒噪！", customFallback)
    }
}
