package com.xiaoxi.android.persona

import com.xiaoxi.android.emotion.model.MoodState

/**
 * 反 AI 腔与自然人味过滤器 (Humanness Gap Filter)
 *
 * 依据《小汐 2.0 开发计划书》5.1.2 节：
 * 本地基于严格正则与语义黑名单，在模型输出阶段实时拦截以下破坏角色沉浸感的机械腔调：
 * 1. AI 身份与模型自称声明（如“作为一个人工智能模型”、“我只是一个AI”）
 * 2. 机械客服应答与套话（如“祝您生活愉快”、“请问有什么可以帮您”、“好的呢”）
 * 3. 空泛敷衍与万能鸡汤（如“我理解您的感受”、“一切都会好起来的”）
 * 4. 机械免责声明与说教（如“建议您寻求专业心理咨询”）
 *
 * 支持双层防护：
 * - 本地手术式降级改写 (Local Surgical Rewriting) 与人设保底回应
 * - 二次自纠环 (Secondary Self-Correction Loop / LLM Re-prompting)
 */
class HumannessFilter(
    private val customRules: List<BannedRule> = emptyList()
) {

    enum class ViolationType(val description: String) {
        AI_IDENTITY("AI身份与模型自称"),
        CUSTOMER_SERVICE("机械客服套话与应答"),
        EMPTY_FORMULA("空泛敷衍与万能鸡汤"),
        ROBOTIC_PREACHY("机械免责与说教声明")
    }

    data class Violation(
        val type: ViolationType,
        val matchedPattern: String,
        val snippet: String
    )

    data class BannedRule(
        val type: ViolationType,
        val regex: Regex
    )

    data class FilterResult(
        val passed: Boolean,
        val originalText: String,
        val filteredText: String,
        val violations: List<Violation>,
        val isRewritten: Boolean
    )

    private val rules: List<BannedRule> = defaultRules() + customRules

    /**
     * 检测文本中存在的所有违禁项
     */
    fun detectViolations(text: String): List<Violation> {
        val violations = mutableListOf<Violation>()
        for (rule in rules) {
            val matches = rule.regex.findAll(text)
            for (match in matches) {
                violations.add(
                    Violation(
                        type = rule.type,
                        matchedPattern = match.value,
                        snippet = extractSnippet(text, match.range)
                    )
                )
            }
        }
        return violations
    }

    /**
     * 判定文本中是否包含任何违禁特征
     */
    fun containsViolations(text: String): Boolean {
        return rules.any { it.regex.containsMatchIn(text) }
    }

    /**
     * 执行过滤并返回结构化结果
     */
    fun filter(text: String, moodState: MoodState? = null): FilterResult {
        val violations = detectViolations(text)
        return if (violations.isEmpty()) {
            FilterResult(
                passed = true,
                originalText = text,
                filteredText = text,
                violations = emptyList(),
                isRewritten = false
            )
        } else {
            FilterResult(
                passed = false,
                originalText = text,
                filteredText = rewriteLocally(text, moodState),
                violations = violations,
                isRewritten = true
            )
        }
    }

    /**
     * 本地降级改写：先尝试手术式清洗违禁片段，若清洗后变空或仍有违禁，则触发基于情绪的性格保底
     */
    fun rewriteLocally(
        text: String,
        moodState: MoodState? = null,
        fallbackProvider: ((MoodState?) -> String)? = null
    ): String {
        var cleaned = text
        for (rule in rules) {
            cleaned = rule.regex.replace(cleaned, "")
        }

        // 清洗连续重复或冲突标点（如 "。，", "，。", "。。", "，，"）
        cleaned = cleaned
            .replace(Regex("[。.]+[，,]+|[，,]+[。.]+"), "。")
            .replace(Regex("([，,]){2,}"), "，")
            .replace(Regex("([。.]){2,}"), "。")
            .replace(Regex("([！？!?~]){2,}")) { it.value.first().toString() }
            .replace(Regex("^[，,、。！？!?~\\s]+"), "")
            .replace(Regex("[，,、\\s]+$"), "")
            .trim()

        // 若清洗后为空、只有标点或仍然含有违禁，则采用人设保底回应
        return if (cleaned.isBlank() || containsViolations(cleaned)) {
            fallbackProvider?.invoke(moodState) ?: getFallbackForMood(moodState)
        } else {
            cleaned
        }
    }

    /**
     * 二次自纠环：优先发起轻量重新提示（Re-prompt），自纠失败或超限时落入本地保底
     */
    suspend fun filterWithCorrectionLoop(
        text: String,
        moodState: MoodState? = null,
        maxRetries: Int = 1,
        fallbackProvider: ((MoodState?) -> String)? = null,
        rePromptFn: suspend (correctionInstruction: String) -> String
    ): FilterResult {
        val initialViolations = detectViolations(text)
        if (initialViolations.isEmpty()) {
            return FilterResult(
                passed = true,
                originalText = text,
                filteredText = text,
                violations = emptyList(),
                isRewritten = false
            )
        }

        var currentText = text
        var currentViolations = initialViolations

        for (attempt in 1..maxRetries) {
            val instruction = buildCorrectionInstruction(currentViolations)
            val candidate = rePromptFn(instruction)
            val candidateViolations = detectViolations(candidate)
            if (candidateViolations.isEmpty()) {
                return FilterResult(
                    passed = true,
                    originalText = text,
                    filteredText = candidate,
                    violations = initialViolations,
                    isRewritten = true
                )
            }
            currentText = candidate
            currentViolations = candidateViolations
        }

        // 多次自纠失败，触发本地安全改写
        val localFallback = rewriteLocally(currentText, moodState, fallbackProvider)
        return FilterResult(
            passed = true,
            originalText = text,
            filteredText = localFallback,
            violations = initialViolations,
            isRewritten = true
        )
    }

    private fun buildCorrectionInstruction(violations: List<Violation>): String {
        val violationList = violations.map { "${it.type.description}（命中：${it.matchedPattern}）" }
            .distinct()
            .joinToString("、")
        return "【违禁检测与语气自纠】你上一轮回复中检测到了如下反角色违禁与客服腔：$violationList。" +
                "请严格以真人陪伴者（小汐）的自然口语重新回答：句子简短利落、带真实情绪、严禁任何客服腔（如“好的呢”、“祝您生活愉快”）与 AI 声明（如“作为一个AI”）。"
    }

    private fun extractSnippet(text: String, range: IntRange): String {
        val start = (range.first - 10).coerceAtLeast(0)
        val end = (range.last + 10).coerceAtMost(text.length)
        return text.substring(start, end)
    }

    private fun getFallbackForMood(moodState: MoodState?): String {
        return when (moodState) {
            MoodState.HAPPY, MoodState.EXCITED, MoodState.LIVELY -> "好呀，那我们聊点有意思的吧！"
            MoodState.SULKING -> "……这事我不太想说，换个话题吧。"
            MoodState.LAZY, MoodState.SLEEPY, MoodState.DAZE -> "唔……有点走神了，你刚才说什么来着？"
            MoodState.CURIOUS, MoodState.EXPECTANT -> "诶？这个我也不太清楚，要不你跟我讲讲？"
            MoodState.CONCERNED -> "……我在这里陪着你呢，想聊点别的吗？"
            else -> "这个我一下子不知道怎么说，换个话题嘛。"
        }
    }

    companion object {
        fun defaultRules(): List<BannedRule> = listOf(
            // 1. AI 身份与模型自称
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("作为一个?(?:人工智能|AI|语言|大型语言)?模型[，,]?")
            ),
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("作为(?:一个)?(?:AI(?:助手|伴侣|机器人)?|人工智能(?:助手)?|虚拟助手|聊天机器人)[，,]?")
            ),
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("身(?:为|在)(?:一个)?(?:AI(?:助手)?|人工智能(?:助手)?|语言模型|聊天机器人)[，,]?")
            ),
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("作为(?:您|你)的(?:虚拟|智能|AI)?助手[，,]?")
            ),
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("本(?:AI|助手|程序)(?:无法|不能|会|可以)[，,]?")
            ),
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("我(?:只|仅仅)?是一个?(?:AI|人工智能|程序|虚拟助手|语言模型|聊天机器人)[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("我没有(?:真实(?:的)?)?(?:感情|情感|身体|肉体|实体)[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("我只是(?:一段)?代码[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.AI_IDENTITY,
                Regex("我不是(?:一个)?真实的人|我不是真的[，,！!。.]?")
            ),

            // 2. 机械客服套话与应答
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("(?:希望)?(?:这些|本|以上|上述)?(?:回答|建议|内容|信息)?(?:能够)?对(?:您|你)(?:有所)?(?:帮助|启发|助益)[！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("(?:请问)?有什么(?:可以|我能)?(?:为(?:您|你))?帮(?:助)?(?:您|你)(?:的)?[？?]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("(?:非常)?(?:荣幸|高兴)为(?:您|你)服务[！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("随时为(?:您|你)效劳[！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("祝您生活愉快[！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("如果(?:您|你)?还有(?:任何)?(?:其他)?(?:问题|疑问|想了解的|想聊的)[，,]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("(?:欢迎)?随时(?:向我提问|找我(?:聊聊)?|告诉我|与我交流)[！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("随时找我哦[！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("好的呢(?:亲)?[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("明白啦(?:亲)?[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("收到您的(?:反馈|消息|建议)[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("感谢您的(?:理解与支持|支持与理解|理解|耐心)[！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("竭诚为您服务[！!。.]?")
            ),
            BannedRule(
                ViolationType.CUSTOMER_SERVICE,
                Regex("我无法(?:回答|协助|提供|解决)(?:您|你)?的?(?:问题|请求|提问)?[，,！!。.]?")
            ),

            // 3. 空泛敷衍与万能鸡汤
            BannedRule(
                ViolationType.EMPTY_FORMULA,
                Regex("我(?:非常|十分|完全)?理解您的(?:感受|心情|处境)[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.EMPTY_FORMULA,
                Regex("一切都会好起来的[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.EMPTY_FORMULA,
                Regex("请保持(?:积极|乐观)的心态[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.EMPTY_FORMULA,
                Regex("不用客气[，,]?这是我(?:应该|分内)做的[！!。.]?")
            ),
            BannedRule(
                ViolationType.EMPTY_FORMULA,
                Regex("加油哦[！!~？?]*")
            ),

            // 4. 机械免责与说教声明
            BannedRule(
                ViolationType.ROBOTIC_PREACHY,
                Regex("建议您寻求专业(?:的)?(?:心理|医疗|医生|法律)?(?:咨询|帮助|意见)[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.ROBOTIC_PREACHY,
                Regex("(?:具体)?请以官方(?:发布|信息|通知)为准[，,！!。.]?")
            ),
            BannedRule(
                ViolationType.ROBOTIC_PREACHY,
                Regex("(?:以上内容仅供参考|请注意甄别信息真实性)[，,！!。.]?")
            )
        )
    }
}
