package com.xiaoxi.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoxi.android.BuildConfig
import com.xiaoxi.android.database.dao.ChatDialogueDao
import com.xiaoxi.android.database.entity.ChatDialogueEntity
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.database.memory.MemoryExtractor
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
import com.xiaoxi.android.persona.model.CharacterCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

class ChatViewModel(
    private val llmClient: XiaoxiLlmClient,
    private val memoryManager: DualTrackMemoryManager,
    private val emotionEngine: EmotionEngine,
    private val phiMiddleware: PhiMiddleware = PhiMiddleware(),
    private val humannessFilter: HumannessFilter = HumannessFilter(),
    private val chatDialogueDao: ChatDialogueDao? = null,
    private val memoryExtractor: MemoryExtractor? = null,
    private val llmConfig: LlmConfig = LlmConfig(apiKey = BuildConfig.DEEPSEEK_API_KEY),
    private val sessionId: String = "default_session"
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(
            currentMood = emotionEngine.currentMood,
            currentEmotionVector = emotionEngine.currentVector
        )
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
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

                val dynamicContext = memoryManager.assembleDynamicContext(
                    query = trimmed,
                    moodValence = currentMood.defaultVector.valence,
                    charName = _uiState.value.activeCharacterCard?.name ?: PhiMiddleware.DEFAULT_CHAR_NAME
                )

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
                val history = currentMessages
                    .dropLast(1)
                    .map { item ->
                        ChatMessage(
                            role = if (item.sender == MessageSender.USER) "user" else "assistant",
                            content = item.text
                        )
                    }

                // 防漂移护盾：记录本轮对话轮次并传入装配（驱动 PhiMiddleware 内部触发器）
                val turnCount = phiMiddleware.recordTurn(sessionId)

                val assembledMessages = phiMiddleware.assemble(
                    characterCard = _uiState.value.activeCharacterCard,
                    dynamicContext = dynamicContext,
                    historyMessages = history,
                    moodState = currentMood,
                    sessionId = sessionId,
                    charName = _uiState.value.activeCharacterCard?.name ?: PhiMiddleware.DEFAULT_CHAR_NAME,
                    turnCount = turnCount
                )

                // 5. 流式打字机累积
                val accumulated = StringBuilder()
                llmClient.streamChat(llmConfig, assembledMessages).collect { event ->
                    when (event) {
                        is LlmStreamEvent.Token -> {
                            accumulated.append(event.text)
                            val currentText = accumulated.toString()
                            _uiState.update { state ->
                                val updated = state.messages.map { msg ->
                                    if (msg.id == assistantMessageId) msg.copy(text = currentText) else msg
                                }
                                state.copy(messages = updated)
                            }
                        }
                        is LlmStreamEvent.Error -> {
                            throw event.throwable
                        }
                        is LlmStreamEvent.Done -> {}
                        else -> {}
                    }
                }

                // 6. 流式完成：反 AI 腔过滤与二次自纠环改写
                val rawResponse = accumulated.toString()
                val filterResult = humannessFilter.filterWithCorrectionLoop(
                    text = rawResponse,
                    moodState = currentMood,
                    maxRetries = 1
                ) { instruction ->
                    val correctionMessages = assembledMessages + listOf(
                        ChatMessage(role = "assistant", content = rawResponse),
                        ChatMessage(role = "user", content = instruction)
                    )
                    runCatching { llmClient.chat(llmConfig, correctionMessages) }.getOrDefault("")
                }
                val finalText = filterResult.filteredText

                _uiState.update { state ->
                    val updated = state.messages.map { msg ->
                        if (msg.id == assistantMessageId) msg.copy(text = finalText) else msg
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
}
