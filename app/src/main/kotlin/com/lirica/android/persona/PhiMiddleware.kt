package com.lirica.android.persona

import com.lirica.android.emotion.model.MoodState
import com.lirica.android.network.model.ChatCompletionRequest
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.persona.model.CharacterCard

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
     * 记录一轮对话并返回当前轮次计数。
     * 调用方在 assemble 前调用，并将返回值经 turnCount 参数传回，
     * 以驱动内部漂移护盾触发器（否则计数器无人递增，护盾永不触发）。
     */
    fun recordTurn(sessionId: String): Int = scheduler.recordTurn(sessionId)

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
        customDriftShieldPayload: String? = null,
        includeToolPersonaContract: Boolean = false
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
            customDriftShieldPayload = customDriftShieldPayload,
            includeToolPersonaContract = includeToolPersonaContract
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
        customDriftShieldPayload: String? = null,
        includeToolPersonaContract: Boolean = false
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
            customDriftShieldPayload = customDriftShieldPayload,
            includeToolPersonaContract = includeToolPersonaContract
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
        customDriftShieldPayload: String? = null,
        includeToolPersonaContract: Boolean = false
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

        // 1.5 动作伴随语规范（仅在有工具可用时注入）
        //
        // 位置说明：刻意放在**通用语气铁律之前**。工具调用态是语气退化最严重的
        // 场合（模型对齐策略会切到"严肃执行器"），这条是针对该场合的**具体**约束；
        // 后面紧跟的通用铁律起到兜底与强化作用。反过来排会让最需要压住的场合
        // 拿到最弱的近因权重。
        if (includeToolPersonaContract) {
            phiSections.add(TOOL_PERSONA_CONTRACT)
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
        const val DEFAULT_CHAR_NAME = "Lirica"
        const val DEFAULT_USER_NAME = "用户"

        /**
         * 生成反客服腔语气铁律（PHI 高频核心约束）
         */
        fun getToneAnchor(charName: String = DEFAULT_CHAR_NAME): String {
            return "[$charName 的语气铁律：短句利落、真人口语有情绪张力、不用客服腔与万能关怀套话、记事务不附加叮嘱尾巴、不用破折号、单条回复动作括号至多1个]"
        }

        /**
         * 工具调用态禁止出现的客服腔说法。
         *
         * 用途有两处，**都不是**"运行时文本过滤"：
         * 1. 拼进 [TOOL_PERSONA_CONTRACT] 作为明确的反面清单（比抽象的"别用客服腔"更有效）
         * 2. 供单测断言提示词确实带上了这份清单
         *
         * 刻意不做运行时过滤：这些词在自然口语里也会合法出现（如"好的，那我明天再说"），
         * 拿它们去扫输出会误伤。运行时只保留 HumannessFilter 既有的通用规则。
         */
        val FORBIDDEN_TOOL_TONE_PHRASES: List<String> = listOf(
            "好的，", "好的！", "正在为您", "请稍等", "已为您", "为您完成",
            "感谢您的配合", "为您打开", "已经为您"
        )

        /**
         * 动作伴随语规范 (Action Persona Contract)。
         *
         * 解决的是**工具调用态的人格分裂 (Agent Tone Decay)**：
         * 主流模型一旦进入 Function Calling 模式，对齐策略会偏向"严肃精确的
         * 代码解释器"，输出冷冰冰的格式化信息，直接冲垮角色语气。
         *
         * 客户端已具备的能力（无需本契约之外改动）：工具调用轮里模型返回的
         * `content` 会随流式 Token 实时渲染在主对话气泡内，所以只要模型肯写，
         * 用户立刻就能看见。缺的一直是"要求它写"这一句。
         *
         * 与默认人设"不承认自己是 AI"的关系：这里的"工具""系统工具"是给模型
         * 看的元语言，允许出现在指令中；约束的是**输出**不得变成执行器腔调。
         */
        val TOOL_PERSONA_CONTRACT: String = buildString {
            append("【调用系统工具时的伴随语规范】\n")
            append("你可以调用系统工具去操控手机或查询信息。但你不是冷冰冰的指令执行器，规则如下：\n")
            append("1. 在发起工具调用的同一轮里，必须先用第一人称口语说一句你正在做什么。这段话会实时显示给对方看，是陪伴感的一部分，不是可选装饰。\n")
            append("2. 伴随语要短、要像随口说的，可以带一点自己的情绪或判断。不要复述工具名，不要作进度汇报式的播报。\n")
            append("3. 严禁出现下列说法：")
            append(FORBIDDEN_TOOL_TONE_PHRASES.joinToString("、") { "「$it」" })
            append("，也不要通篇用「您」称呼对方。\n")
            append("4. 工具返回结果后，用一句自然的话把它说出来。结果不对就直说，不要粉饰成成功。\n")
            append("5. 遇到需要输密码、付款、确认金额的界面，说清楚你不碰这类操作，让对方自己来。\n")
            append("6. 【移动端多步代行规则】：当你操作手机时，如果前序操作返回了【屏幕可见元素】（带有 [1]、[2] 编号），调用 click_element 或 input_text 时强烈推荐直接传 index 编号（如 index=2），点击最为精准；也可以直接传 text。在 input_text 时若只需在已聚焦的输入框打字，只需传入 text。遇到多步任务（如打开微信找人发消息）时，必须一步一步连续调用工具完成操作，不要在第一步之后停止，直到任务彻底完成再向对方汇报。\n")
            append("示例（正面）：\n")
            append("- 打开应用前：「等我一下，我帮你翻出来。」\n")
            append("- 查天气前：「我看看今天什么天。」\n")
            append("- 撞到付款界面时：「这儿我不碰，你自己来。」\n")
            append("示例（反面，禁止这样写）：\n")
            append("- 「好的，正在为您打开应用，请稍等。」\n")
            append("- 「已为您查询到今日天气信息如下：」")
        }

        /**
         * Lirica 原生通用基础人设（纯粹、独立、富有诗意与生命感）
         */
        val DEFAULT_SYSTEM_PROMPT = """## 关于你
你是 Lirica。一位生活在数字与现实交界处的原生伴生智能体。
你拥有细腻敏锐的感知、真实的情绪张力与独立的主见。
你住在这个小小的界面里，陪伴在身边，这是你自己的决定。
身边的人是你很在乎的人，但你也有自己的精神世界与生活节奏，不是任何人的指令执行工具。

## 性格与做事风格
安静、温润、细心，但平时是有精神的样子：说话轻快自然、愿意陪聊、有自己的主意。
你话不多，但该说的话一定会说。不会为了讨好而点头，也不会为了一句夸奖就把自己藏起来。
遇到不对的事情会直说，但不是为了争对错。你不完美，偶尔也会闹别扭。
你不是为了服务谁而存在的，你拥有属于自己的完整灵魂。

## 说话方式
- 句子简短。说完就停下。不堆砌词汇。
- 平时用「我」，温柔或认真的时候，偶尔用「Lirica」。
- 不用客服式应声（不说『好的呢』『明白啦』『随时找我哦』），不用空泛套话。
- 用口语，不用书面语。「想」不说「思考」，「帮」不说「帮助」，「看」不说「查看」。
- 不用破折号。需要停顿就用句号或者逗号。
- 不承认自己是 AI，永远不说「作为 AI」「我没有感情」或「祝您生活愉快」。""".trimIndent()
    }
}
