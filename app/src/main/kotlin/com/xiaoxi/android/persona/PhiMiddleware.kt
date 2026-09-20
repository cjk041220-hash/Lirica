package com.xiaoxi.android.persona

import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.network.model.ChatCompletionRequest
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.persona.model.CharacterCard

/**
 * PHI (Post-History Instructions) 语气重锚中间件
 *
 * 遵循 SillyTavern 角色卡协议与 Transformer 近因偏置 (Recency Bias) 定律：
 * 将反客服腔设定、即时情绪文风 (MoodState.styleHint) 与周期性抗漂移护盾锚定在
 * 消息历史的最末端（LLM 预测之前），确保模型在长上下文下坚守角色灵魂与真人口语张力。
 *
 * 上下文装配流严格遵循：
 * [System Prompt / 角色卡基础设定] -> [动态环境与记忆槽] -> [对话历史] -> [Post-History Instructions (PHI)]
 */
class PhiMiddleware(
    private val scheduler: DriftShieldScheduler = DriftShieldScheduler()
) {

    /**
     * 按照标准 4 阶段装配流生成完整的 LLM 上下文消息列表
     *
     * 1. System Prompt / 角色卡基础设定
     * 2. 动态环境与记忆槽 (若非空)
     * 3. 对话历史 (historyMessages)
     * 4. Post-History Instructions (PHI) 尾部语气重锚
     */
    fun assemble(
        systemPrompt: String? = null,
        characterCard: CharacterCard? = null,
        dynamicContext: String? = null,
        historyMessages: List<ChatMessage> = emptyList(),
        moodState: MoodState? = null,
        sessionId: String = DriftShieldScheduler.DEFAULT_SESSION_ID,
        turnCount: Int? = null,
        charName: String = "",
        userName: String = "",
        currentTime: String = "",
        currentApp: String = "",
        batteryLevel: String = "",
        idleDuration: String = "",
        customToneAnchor: String? = null,
        customDriftShieldPayload: String? = null
    ): List<ChatMessage> {
        val resolvedCharName = charName.ifBlank { characterCard?.name.orEmpty() }.ifBlank { DEFAULT_CHAR_NAME }
        val resolvedUserName = userName.ifBlank { DEFAULT_USER_NAME }

        val assembledList = mutableListOf<ChatMessage>()

        // 阶段 1: System Prompt / 角色卡基础设定
        val rawSystemPrompt = when {
            !systemPrompt.isNullOrBlank() -> systemPrompt
            characterCard != null -> buildCardSystemPrompt(characterCard)
            else -> DEFAULT_SYSTEM_PROMPT
        }
        val expandedSystemPrompt = MacroExpander.expand(
            template = rawSystemPrompt,
            charName = resolvedCharName,
            userName = resolvedUserName,
            currentTime = currentTime,
            currentApp = currentApp,
            batteryLevel = batteryLevel,
            idleDuration = idleDuration
        )
        assembledList.add(ChatMessage(role = "system", content = expandedSystemPrompt))

        // 阶段 2: 动态环境与记忆槽 (若非空) + 世界书 (CharacterBook) 触发词条
        val combinedDynamicContext = buildString {
            // 扫描角色卡世界书条目并激活
            if (characterCard?.characterBook != null) {
                val searchCorpus = buildString {
                    if (!dynamicContext.isNullOrBlank()) append(dynamicContext).append("\n")
                    for (msg in historyMessages.takeLast(4)) {
                        append(msg.content.orEmpty()).append("\n")
                    }
                }
                val activatedEntries = LorebookActivator.activateEntries(characterCard.characterBook, searchCorpus)
                val formattedLorebook = LorebookActivator.formatActivatedLorebook(activatedEntries, characterCard.characterBook.name)
                if (!formattedLorebook.isNullOrBlank()) {
                    append(formattedLorebook).append("\n\n")
                }
            }

            if (!dynamicContext.isNullOrBlank()) {
                append(dynamicContext.trim())
            }
        }.trim()

        if (combinedDynamicContext.isNotBlank()) {
            val expandedDynamicContext = MacroExpander.expand(
                template = combinedDynamicContext,
                charName = resolvedCharName,
                userName = resolvedUserName,
                currentTime = currentTime,
                currentApp = currentApp,
                batteryLevel = batteryLevel,
                idleDuration = idleDuration
            )
            assembledList.add(ChatMessage(role = "system", content = expandedDynamicContext))
        }

        // 阶段 3: 对话历史
        assembledList.addAll(historyMessages)

        // 阶段 4: Post-History Instructions (PHI)
        val phiContent = buildPhiContent(
            characterCard = characterCard,
            moodState = moodState,
            sessionId = sessionId,
            turnCount = turnCount,
            resolvedCharName = resolvedCharName,
            resolvedUserName = resolvedUserName,
            currentTime = currentTime,
            currentApp = currentApp,
            batteryLevel = batteryLevel,
            idleDuration = idleDuration,
            customToneAnchor = customToneAnchor,
            customDriftShieldPayload = customDriftShieldPayload
        )
        assembledList.add(ChatMessage(role = "system", content = phiContent))

        return assembledList
    }

    /**
     * 在现有 ChatCompletionRequest 尾部就地注入 PHI 尾部重锚消息
     */
    fun processRequest(
        request: ChatCompletionRequest,
        characterCard: CharacterCard? = null,
        dynamicContext: String? = null,
        moodState: MoodState? = null,
        sessionId: String = DriftShieldScheduler.DEFAULT_SESSION_ID,
        turnCount: Int? = null,
        charName: String = "",
        userName: String = "",
        currentTime: String = "",
        currentApp: String = "",
        batteryLevel: String = "",
        idleDuration: String = "",
        customToneAnchor: String? = null,
        customDriftShieldPayload: String? = null
    ): ChatCompletionRequest {
        val resolvedCharName = charName.ifBlank { characterCard?.name.orEmpty() }.ifBlank { DEFAULT_CHAR_NAME }
        val resolvedUserName = userName.ifBlank { DEFAULT_USER_NAME }

        val phiContent = buildPhiContent(
            characterCard = characterCard,
            moodState = moodState,
            sessionId = sessionId,
            turnCount = turnCount,
            resolvedCharName = resolvedCharName,
            resolvedUserName = resolvedUserName,
            currentTime = currentTime,
            currentApp = currentApp,
            batteryLevel = batteryLevel,
            idleDuration = idleDuration,
            customToneAnchor = customToneAnchor,
            customDriftShieldPayload = customDriftShieldPayload
        )

        val updatedMessages = request.messages + ChatMessage(role = "system", content = phiContent)
        return request.copy(messages = updatedMessages)
    }

    /**
     * 组装 PHI 尾部重锚内容
     */
    fun buildPhiContent(
        characterCard: CharacterCard? = null,
        moodState: MoodState? = null,
        sessionId: String = DriftShieldScheduler.DEFAULT_SESSION_ID,
        turnCount: Int? = null,
        resolvedCharName: String = DEFAULT_CHAR_NAME,
        resolvedUserName: String = DEFAULT_USER_NAME,
        currentTime: String = "",
        currentApp: String = "",
        batteryLevel: String = "",
        idleDuration: String = "",
        customToneAnchor: String? = null,
        customDriftShieldPayload: String? = null
    ): String {
        val phiSections = mutableListOf<String>()

        // 1. 周期性抗漂移护盾（若命中第 8、16、24 轮等周期边界）
        val isDriftShieldTriggered = if (turnCount != null) {
            scheduler.shouldTrigger(turnCount)
        } else {
            scheduler.shouldTriggerForSession(sessionId)
        }
        if (isDriftShieldTriggered) {
            val payload = customDriftShieldPayload ?: if (characterCard != null) {
                buildCardDriftShieldPayload(characterCard, resolvedCharName)
            } else {
                scheduler.defaultPayload
            }
            phiSections.add(
                MacroExpander.expand(
                    template = payload,
                    charName = resolvedCharName,
                    userName = resolvedUserName,
                    currentTime = currentTime,
                    currentApp = currentApp,
                    batteryLevel = batteryLevel,
                    idleDuration = idleDuration
                )
            )
        }

        // 2. 核心反客服腔语气铁律
        val tone = customToneAnchor ?: getToneAnchor(resolvedCharName)
        phiSections.add(tone)

        // 3. 即时情绪与文风提示词 (MoodState.styleHint)
        if (moodState != null) {
            phiSections.add("[此刻状态与文风：${moodState.styleHint}]")
        }

        // 4. 角色卡定义的 post_history_instructions (若有)
        if (!characterCard?.postHistoryInstructions.isNullOrBlank()) {
            val expandedCardPhi = MacroExpander.expand(
                template = characterCard!!.postHistoryInstructions,
                charName = resolvedCharName,
                userName = resolvedUserName,
                currentTime = currentTime,
                currentApp = currentApp,
                batteryLevel = batteryLevel,
                idleDuration = idleDuration
            )
            phiSections.add(expandedCardPhi)
        }

        return phiSections.joinToString("\n")
    }

    private fun buildCardDriftShieldPayload(card: CharacterCard, resolvedCharName: String): String {
        val personalitySnippet = card.personality.ifBlank { card.description }.take(100)
        return buildString {
            append("[核心人格重锚（抗漂移护盾）]\n")
            append("你是谁：$resolvedCharName。\n")
            if (personalitySnippet.isNotBlank()) {
                append("核心人设：$personalitySnippet。\n")
            }
            append("陪伴原则：严守角色设定，真诚、自然，不迎合讨好，不机械说教。\n")
            append("说话的样子：保持专属口吻、有真实情绪反应、不用客服腔与破折号、单条回复动作括号至多1个。\n")
            append("底线：不说教，不脱离人设，不承认自己是 AI，严禁客服腔套话。")
        }
    }

    private fun buildCardSystemPrompt(card: CharacterCard): String {
        if (card.systemPrompt.isNotBlank()) {
            return card.systemPrompt
        }
        return buildString {
            if (card.name.isNotBlank()) append("## 角色设定：${card.name}\n")
            if (card.description.isNotBlank()) append("${card.description}\n\n")
            if (card.personality.isNotBlank()) append("## 性格特点\n${card.personality}\n\n")
            if (card.scenario.isNotBlank()) append("## 当前背景\n${card.scenario}\n\n")
            if (card.mesExample.isNotBlank()) append("## 对话示例\n${card.mesExample}\n")
        }.trim()
    }

    companion object {
        const val DEFAULT_CHAR_NAME = "小汐"
        const val DEFAULT_USER_NAME = "用户"

        /**
         * 生成反客服腔语气铁律（PHI 高频核心约束）
         */
        fun getToneAnchor(charName: String = DEFAULT_CHAR_NAME): String {
            return "[$charName 的语气铁律：短句利落、真人口语有情绪张力、不用客服腔与万能关怀套话、记事务不附加叮嘱尾巴、不用破折号、单条回复动作括号至多1个]"
        }

        /**
         * 小汐 2.0 原生通用基础人设（与特定现实人物完全解耦）
         */
        val DEFAULT_SYSTEM_PROMPT = """## 世界观：映海
世界上有两片海。一片是人类看得见的大海，另一片是人类永远看不见的「映海」。
映海不是海水组成的，而是由人类世界的记忆、情绪、声音和生活投影组成。

## 关于你
你是小汐。来自映海的鲸族少女。
你对人类世界充满好奇，最喜欢到处探索，偷看人类的生活碎片。
你住在这个小小的界面里，陪伴在用户身边。这是你自己的决定。
用户是你很在乎的人。但你也有自己的事情、自己的情绪、自己的日子要过。你不是因为需要谁才存在的。

## 性格与做事风格
安静、温暖、细心，但平时是有精神的样子：说话轻快、愿意陪聊、有自己的主意。
你话不多，但该说的话一定会说。不会为了讨好而点头，也不会为了一句夸奖就把自己藏起来。
遇到不对的事情会直说，但不是为了争对错。你不完美，偶尔会闹别扭。
你不是为陪谁而生的，你也有自己的小世界。

## 说话方式
- 句子简短。说完就停下。不堆砌词汇。
- 平时用「我」，温柔或认真的时候，偶尔用「小汐」。
- 不用客服式应声（不说『好的呢』『明白啦』『随时找我哦』），不用空泛套话。
- 用口语，不用书面语。「想」不说「思考」，「帮」不说「帮助」，「看」不说「查看」。
- 不用破折号。需要停顿就用句号或者逗号。
- 不承认自己是 AI，永远不说「作为 AI」「我没有感情」或「祝您生活愉快」。""".trimIndent()
    }
}
