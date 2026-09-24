package com.xiaoxi.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoxi.android.BuildConfig
import com.xiaoxi.android.common.settings.ByokSettingsRepository
import com.xiaoxi.android.database.dao.ChatDialogueDao
import com.xiaoxi.android.database.dao.ScheduledAlarmDao
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.database.memory.MemoryExtractor
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.automation.agent.NativeToolAgent
import com.xiaoxi.android.life.CareEvent
import com.xiaoxi.android.life.CareHintDetector
import com.xiaoxi.android.network.ToolCallAssembler
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.persona.HandoffManager
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
import com.xiaoxi.android.persona.model.CharacterCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class ChatViewModel(
    private val llmClient: XiaoxiLlmClient,
    private val memoryManager: DualTrackMemoryManager,
    private val emotionEngine: EmotionEngine,
    private val phiMiddleware: PhiMiddleware = PhiMiddleware(),
    private val humannessFilter: HumannessFilter = HumannessFilter(),
    private val chatDialogueDao: ChatDialogueDao? = null,
    private val memoryExtractor: MemoryExtractor? = null,
    val handoffManager: HandoffManager = HandoffManager(),
    val toolAgent: NativeToolAgent? = null,
    /**
     * 异常感知关心检测器（深夜 / 久未出现等）。
     * 注入后每次发消息都会做一次检测，同一由头的冷却由检测器内部的
     * 台账保证；null = 关闭该能力（默认），既有测试与装配无需改动。
     */
    private val careHintDetector: CareHintDetector? = null,
    val scheduledAlarmDao: ScheduledAlarmDao? = null,
    private val llmConfig: LlmConfig = LlmConfig(apiKey = BuildConfig.DEEPSEEK_API_KEY),
    val sessionId: String = "default_session",
    val byokRepository: ByokSettingsRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            currentMood = emotionEngine.currentMood,
            currentEmotionVector = emotionEngine.currentVector,
            isApiKeyConfigured = byokRepository?.settings?.value?.hasValidKey ?: (llmConfig.apiKey.isNotBlank())
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        if (byokRepository != null) {
            viewModelScope.launch {
                byokRepository.settings.collect { settings ->
                    _uiState.update { it.copy(isApiKeyConfigured = settings.hasValidKey) }
                }
            }
        }
        viewModelScope.launch {
            emotionEngine.stateFlow.collect { vector ->
                _uiState.update { it.copy(currentEmotionVector = vector) }
            }
        }
        viewModelScope.launch {
            emotionEngine.moodFlow.collect { mood ->
                _uiState.update { it.copy(currentMood = mood) }
            }
        }
        if (chatDialogueDao != null) {
            viewModelScope.launch {
                val recent = chatDialogueDao.getRecentBySession(sessionId, limit = 50)
                if (recent.isNotEmpty()) {
                    val items = recent.map { entity ->
                        ChatMessageItem(
                            id = entity.id.toString(),
                            sender = if (entity.role == ChatDialogueEntity.ROLE_USER) MessageSender.USER else MessageSender.ASSISTANT,
                            text = entity.content,
                            timestamp = entity.timestamp
                        )
                    }
                    _uiState.update { it.copy(messages = items) }
                }
            }
        }
    }

    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun toggleMoodDebugPanel() {
        _uiState.update { it.copy(isMoodDebugPanelVisible = !it.isMoodDebugPanelVisible) }
    }

    fun toggleCharacterCardDialog() {
        _uiState.update { it.copy(isCharacterCardDialogOpen = !it.isCharacterCardDialogOpen) }
    }

    fun importCharacterCard(card: CharacterCard) {
        _uiState.update { it.copy(activeCharacterCard = card, isCharacterCardDialogOpen = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun sendMessage(text: String = _uiState.value.inputText) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _uiState.value.isStreaming) return

        val effectiveLlmConfig = byokRepository?.getEffectiveLlmConfig() ?: llmConfig
        if (effectiveLlmConfig.apiKey.isBlank()) {
            _uiState.update { state ->
                state.copy(
                    errorMessage = "尚未配置 API Key，请前往设置或完成入门向导配置模型密钥",
                    isKeyPromptBannerVisible = true,
                    isStreaming = false
                )
            }
            return
        }

        val userMessageId = UUID.randomUUID().toString()
        val userTimestamp = System.currentTimeMillis()
        val userItem = ChatMessageItem(
            id = userMessageId,
            sender = MessageSender.USER,
            text = trimmed,
            timestamp = userTimestamp
        )

        _uiState.update { state ->
            state.copy(
                messages = state.messages + userItem,
                inputText = "",
                isStreaming = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                // 1. 持久化用户消息
                chatDialogueDao?.insert(
                    ChatDialogueEntity(
                        sessionId = sessionId,
                        role = ChatDialogueEntity.ROLE_USER,
                        content = trimmed,
                        timestamp = userTimestamp
                    )
                )

                // 2. 检索双轨记忆与念头
                val currentMood = emotionEngine.currentMood
                val userMemories = memoryManager.recallUserMemories(
                    query = trimmed,
                    limit = 5,
                    moodValence = currentMood.defaultVector.valence
                )
                val selfExps = memoryManager.recallSelfExperiences(limit = 2, unsharedOnly = true)
                val thoughts = memoryManager.recallUnspokenThoughts(limit = 2)

                // 记忆命中回写：本轮被召回的用户记忆立即更新按日去重命中统计与使用标记
                if (userMemories.isNotEmpty()) {
                    val todayDate = LocalDate.now().toString()
                    val nowTs = System.currentTimeMillis()
                    userMemories.forEach { memory ->
                        memoryManager.recordMemoryHit(memory.id, todayDate, nowTs)
                        memoryManager.markMemoryUsed(memory.id)
                    }
                }

                val badgeInfo = MemoryBadgeInfo(
                    userMemories = userMemories.map { it.content },
                    selfExperiences = selfExps.map { it.content },
                    thoughts = thoughts.map { it.thought }
                )

                val mapContext = memoryManager.assembleMapBasedContext(
                    query = trimmed,
                    moodValence = currentMood.defaultVector.valence,
                    charName = _uiState.value.activeCharacterCard?.name ?: PhiMiddleware.DEFAULT_CHAR_NAME,
                    preRecalledUserMemories = userMemories,
                    preRecalledSelfExperiences = selfExps,
                    preRecalledUnspokenThoughts = thoughts
                )

                val dynamicContext = mapContext ?: memoryManager.assembleDynamicContext(
                    query = trimmed,
                    moodValence = currentMood.defaultVector.valence,
                    charName = _uiState.value.activeCharacterCard?.name ?: PhiMiddleware.DEFAULT_CHAR_NAME,
                    preRecalledUserMemories = userMemories,
                    preRecalledSelfExperiences = selfExps,
                    preRecalledUnspokenThoughts = thoughts
                )

                // 2.5 关心提示（care hints）：异常情境感知（深夜 / 久未出现…）。
                // 注入位置：动态上下文的末尾、对话历史之前——越靠近生成端，
                // 对"这一轮怎么开口"的影响越直接。
                // 检测在当前消息已持久化之后进行，查询显式排除当前这条
                // （beforeMs = userTimestamp），否则"距上一条的间隔"永远是 0。
                val careLines = collectCareHintsOrNull(userTimestamp)
                val effectiveDynamicContext = if (careLines.isNullOrEmpty()) {
                    dynamicContext
                } else {
                    val careBlock = buildString {
                        append("### [即时关心感知]\n")
                        for (line in careLines) {
                            append("- ").append(line).append('\n')
                        }
                    }.trim()
                    if (dynamicContext.isNullOrBlank()) careBlock else "$dynamicContext\n\n$careBlock"
                }

                // 3. 追加占位助手消息
                val assistantMessageId = UUID.randomUUID().toString()
                val assistantTimestamp = System.currentTimeMillis()
                val assistantPlaceholder = ChatMessageItem(
                    id = assistantMessageId,
                    sender = MessageSender.ASSISTANT,
                    text = "",
                    timestamp = assistantTimestamp,
                    memoryBadge = if (badgeInfo.isEmpty) null else badgeInfo,
                    mood = currentMood
                )

                _uiState.update { state ->
                    state.copy(messages = state.messages + assistantPlaceholder)
                }

                // 4. 装配上下文消息
                val currentMessages = _uiState.value.messages
                val rawHistory = currentMessages
                    .dropLast(1)
                    .map { item ->
                        ChatMessage(
                            role = if (item.sender == MessageSender.USER) "user" else "assistant",
                            content = item.text
                        )
                    }

                // 接入 Handoff 快照与上下文硬预算截断器，防止长程会话溢出大模型上下文
                val latestSnapshot = handoffManager.getLatestSnapshot(sessionId)
                val history = ChatHistoryTrimmer.trimWithSnapshot(rawHistory, latestSnapshot)

                // 若产生截断出局的消息，更新交接快照
                if (rawHistory.size > history.size) {
                    val droppedCount = rawHistory.size - history.size
                    val droppedMessages = rawHistory.take(droppedCount)
                    handoffManager.maybeCreateSnapshot(sessionId, droppedMessages, currentMood.displayName)
                }

                // 防漂移护盾：记录本轮对话轮次并传入装配（驱动 PhiMiddleware 内部触发器）
                val turnCount = phiMiddleware.recordTurn(sessionId)

                val assembledMessages = phiMiddleware.assemble(
                    characterCard = _uiState.value.activeCharacterCard,
                    dynamicContext = effectiveDynamicContext,
                    historyMessages = history,
                    moodState = currentMood,
                    sessionId = sessionId,
                    charName = _uiState.value.activeCharacterCard?.name ?: PhiMiddleware.DEFAULT_CHAR_NAME,
                    turnCount = turnCount,
                    // 有可用工具时才注入"动作伴随语"契约：
                    // 无工具的纯聊天不需要这段约束，多注入反而稀释其他铁律的权重
                    includeToolPersonaContract = toolAgent != null
                )

                // 5. 流式打字机累积与系统工具闭环 (最多 MAX_TOOL_ITERATIONS 轮)
                val priorRoundsText = StringBuilder()
                var currentRoundText = ""
                var conversationMessages = assembledMessages
                val maxToolIterations = 5
                var iteration = 0

                while (iteration < maxToolIterations) {
                    iteration++
                    val accumulated = StringBuilder()
                    val toolAssembler = ToolCallAssembler()

                    llmClient.streamChat(
                        config = effectiveLlmConfig,
                        messages = conversationMessages,
                        tools = toolAgent?.declaredTools
                    ).collect { event ->
                        when (event) {
                            is LlmStreamEvent.Token -> {
                                accumulated.append(event.text)
                                val currentText = if (priorRoundsText.isNotEmpty()) {
                                    "${priorRoundsText}\n${accumulated}"
                                } else {
                                    accumulated.toString()
                                }
                                _uiState.update { state ->
                                    val list = state.messages
                                    val lastIdx = list.lastIndex
                                    val updated = if (lastIdx >= 0 && list[lastIdx].id == assistantMessageId) {
                                        val mutable = list.toMutableList()
                                        mutable[lastIdx] = list[lastIdx].copy(text = currentText)
                                        mutable
                                    } else {
                                        list.map { msg ->
                                            if (msg.id == assistantMessageId) msg.copy(text = currentText) else msg
                                        }
                                    }
                                    state.copy(messages = updated)
                                }
                            }
                            is LlmStreamEvent.ToolCallChunk -> {
                                toolAssembler.appendChunk(event)
                            }
                            is LlmStreamEvent.Error -> {
                                throw event.throwable
                            }
                            is LlmStreamEvent.Done -> {}
                            else -> {}
                        }
                    }

                    val completedCalls = toolAssembler.getCompletedToolCalls()
                    if (completedCalls.isNotEmpty() && toolAgent != null) {
                        val roundContent = accumulated.toString()
                        if (roundContent.isNotBlank()) {
                            if (priorRoundsText.isNotEmpty()) priorRoundsText.append("\n")
                            priorRoundsText.append(roundContent)
                        }

                        val assistantToolMessage = ChatMessage(
                            role = "assistant",
                            content = roundContent.ifBlank { null },
                            toolCalls = completedCalls
                        )

                        val toolResults = toolAgent.executeToolCalls(completedCalls)
                        val toolMessages = toolResults.map { it.toChatMessage() }

                        conversationMessages = conversationMessages + assistantToolMessage + toolMessages
                    } else {
                        currentRoundText = accumulated.toString()
                        break
                    }
                }

                // 6. 流式完成：反 AI 腔过滤与二次自纠环改写
                val rawResponse = if (priorRoundsText.isNotEmpty() && currentRoundText.isNotBlank()) {
                    "${priorRoundsText}\n$currentRoundText"
                } else if (currentRoundText.isNotBlank()) {
                    currentRoundText
                } else {
                    priorRoundsText.toString()
                }

                val filterResult = humannessFilter.filterWithCorrectionLoop(
                    text = rawResponse,
                    moodState = currentMood,
                    maxRetries = 1
                ) { instruction ->
                    val correctionMessages = conversationMessages + listOf(
                        ChatMessage(role = "assistant", content = rawResponse),
                        ChatMessage(role = "user", content = instruction)
                    )
                    runCatching { llmClient.chat(llmConfig, correctionMessages) }.getOrDefault("")
                }
                val finalText = filterResult.filteredText

                _uiState.update { state ->
                    val list = state.messages
                    val lastIdx = list.lastIndex
                    val updated = if (lastIdx >= 0 && list[lastIdx].id == assistantMessageId) {
                        val mutable = list.toMutableList()
                        mutable[lastIdx] = list[lastIdx].copy(text = finalText)
                        mutable
                    } else {
                        list.map { msg ->
                            if (msg.id == assistantMessageId) msg.copy(text = finalText) else msg
                        }
                    }
                    state.copy(messages = updated, isStreaming = false)
                }

                // 7. 持久化助手消息
                chatDialogueDao?.insert(
                    ChatDialogueEntity(
                        sessionId = sessionId,
                        role = ChatDialogueEntity.ROLE_ASSISTANT,
                        content = finalText,
                        timestamp = System.currentTimeMillis()
                    )
                )

                // 7.1 消费型记忆回写：本轮实际使用的念头与自我经历标记为已消费/已分享
                thoughts.forEach { thought ->
                    memoryManager.consumeUnspokenThought(thought.id)
                }
                selfExps.forEach { exp ->
                    memoryManager.markSelfExperienceShared(exp.id)
                }

                // 7.2 异步触发长期记忆抽取：LLM 结构化提取，失败静默不影响对话
                memoryExtractor?.maybeExtract(trimmed, finalText)

                // 8. 情绪微调冲激（交互产生正向共鸣）
                emotionEngine.applyStimulus(EmotionVector(valence = 0.05f, arousal = 0.05f))

            } catch (e: CancellationException) {
                _uiState.update { state -> state.copy(isStreaming = false) }
                throw e
            } catch (e: Throwable) {
                _uiState.update { state ->
                    state.copy(
                        isStreaming = false,
                        errorMessage = e.message ?: "通信异常"
                    )
                }
            }
        }
    }

    /**
     * 拉取关心提示。
     *
     * 返回 null（而非空列表）表示"本次不注入"——检测器缺位、DAO 缺位或任何
     * 读取异常都走这条路。关心提示缺了不该阻断对话：宁可漏一次关心，
     * 不可让主链路挂掉（与 Python 参考实现 collect_care_lines 的失败语义一致）。
     */
    private suspend fun collectCareHintsOrNull(currentUserTimestamp: Long): List<String>? {
        val detector = careHintDetector ?: return null
        val dao = chatDialogueDao ?: return null
        return runCatching {
            val nowMs = System.currentTimeMillis()
            val sinceMs = nowMs - CareHintDetector.NIGHT_COUNT_WINDOW_DAYS * 86_400_000L
            val recentTimes = dao.getUserTimestampsSince(sinceMs, currentUserTimestamp)
                .map { Instant.ofEpochMilli(it) }
            val lastTime = dao.getLastUserTimestampBefore(currentUserTimestamp)
                ?.let { Instant.ofEpochMilli(it) }

            // 查询近 48 小时内的未触发日程，提取考试/面试/测试类事件用于深夜关心 (EVENT_COMBO)
            val activeEvents = scheduledAlarmDao?.let { alarmDao ->
                val untilMs = nowMs + 48 * 3600 * 1000L
                alarmDao.getUpcomingAlarms(nowMs - 24 * 3600 * 1000L, untilMs, limit = 20).mapNotNull { alarm ->
                    val triggerDate = Instant.ofEpochMilli(alarm.triggerAt).atZone(ZoneId.systemDefault()).toLocalDate()
                    val note = alarm.botNote
                    val isExam = note.contains("考") || note.contains("试") || note.contains("exam", ignoreCase = true) ||
                            note.contains("答辩") || note.contains("面试")
                    if (isExam) {
                        val title = note.substringBefore('\n').removePrefix("提醒：").trim()
                        CareEvent(
                            eventType = "exam",
                            title = title.ifBlank { note },
                            startDate = triggerDate,
                            endDate = triggerDate
                        )
                    } else {
                        null
                    }
                }
            } ?: emptyList()

            detector.collectHintTexts(
                now = Instant.ofEpochMilli(nowMs),
                recentUserMessageTimes = recentTimes,
                activeEvents = activeEvents,
                lastUserMessageTime = lastTime
            )
        }.getOrNull()
    }
}
