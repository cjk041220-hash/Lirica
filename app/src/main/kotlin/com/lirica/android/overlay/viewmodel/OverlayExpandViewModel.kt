package com.lirica.android.overlay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lirica.android.BuildConfig
import com.lirica.android.automation.agent.NativeToolAgent
import com.lirica.android.common.settings.ByokSettingsRepository
import com.lirica.android.database.dao.ChatDialogueDao
import com.lirica.android.database.dao.UnspokenThoughtDao
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.database.memory.DualTrackMemoryManager
import com.lirica.android.database.memory.MemoryExtractor
import com.lirica.android.emotion.EmotionEngine
import com.lirica.android.emotion.model.EmotionVector
import com.lirica.android.emotion.model.MoodState
import com.lirica.android.network.ToolCallAssembler
import com.lirica.android.network.LiricaLlmClient
import com.lirica.android.network.model.ChatMessage
import com.lirica.android.network.model.LlmConfig
import com.lirica.android.network.model.LlmProvider
import com.lirica.android.network.model.LlmStreamEvent
import com.lirica.android.perception.model.PerceptionSnapshot
import com.lirica.android.persona.HumannessFilter
import com.lirica.android.persona.PhiMiddleware
import com.lirica.android.proactivity.ProactiveBubbleCoordinator
import com.lirica.android.proactivity.ProactivityLevel
import com.lirica.android.proactivity.UserFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 悬浮伴生体展开面板与破冰念头协调 ViewModel
 */
class OverlayExpandViewModel(
    private val unspokenThoughtDao: UnspokenThoughtDao,
    private val emotionEngine: EmotionEngine,
    private val llmClient: LiricaLlmClient,
    private val memoryManager: DualTrackMemoryManager,
    private val phiMiddleware: PhiMiddleware = PhiMiddleware(),
    private val humannessFilter: HumannessFilter = HumannessFilter(),
    val proactiveCoordinator: ProactiveBubbleCoordinator? = null,
    private val llmConfig: LlmConfig = LlmConfig(
        apiKey = BuildConfig.DEEPSEEK_API_KEY,
        provider = LlmProvider.DEEPSEEK,
        model = "deepseek-chat"
    ),
    val byokRepository: ByokSettingsRepository? = null,
    private val chatDialogueDao: ChatDialogueDao? = null,
    private val toolAgent: NativeToolAgent? = null,
    private val memoryExtractor: MemoryExtractor? = null,
    val autoDismissTimeoutMs: Long = DEFAULT_ICEBREAKER_TIMEOUT_MS
) : ViewModel() {

    companion object {
        const val OVERLAY_SESSION_ID = "session_overlay_companion"
        const val MAX_TOOL_ITERATIONS = 5
        const val DEFAULT_ICEBREAKER_TIMEOUT_MS = 15_000L
    }

    private var icebreakerTimeoutJob: Job? = null

    private fun scheduleIcebreakerTimeout(thought: UnspokenThoughtEntity?, timeoutMs: Long = autoDismissTimeoutMs) {
        icebreakerTimeoutJob?.cancel()
        if (thought == null || timeoutMs <= 0L) return
        icebreakerTimeoutJob = viewModelScope.launch {
            try {
                delay(timeoutMs)
                if (_uiState.value.icebreakerThought?.id == thought.id) {
                    onIcebreakerIgnored(thought)
                }
            } catch (e: CancellationException) {
                // 协程正常取消
            }
        }
    }

    private val _uiState = MutableStateFlow(
        OverlayExpandUiState(currentMood = emotionEngine.currentMood)
    )
    val uiState: StateFlow<OverlayExpandUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            emotionEngine.moodFlow.collect { mood ->
                _uiState.update { it.copy(currentMood = mood) }
            }
        }
    }

    /**
     * 响应端侧多维情境感知快照，通过主动协调中枢 (ProactiveBubbleCoordinator) 驱动分级呈现
     */
    fun processPerception(snapshot: PerceptionSnapshot, nowMs: Long = System.currentTimeMillis()) {
        val coordinator = proactiveCoordinator ?: return
        viewModelScope.launch {
            try {
                val result = coordinator.coordinate(snapshot, nowMs)
                when (result.level) {
                    ProactivityLevel.L2_ICEBREAKER_BUBBLE -> {
                        _uiState.update { it.copy(icebreakerThought = result.thought) }
                        scheduleIcebreakerTimeout(result.thought)
                    }
                    ProactivityLevel.L0_SILENT -> {
                        // L0: 静默更新内部动力学，若已有破冰气泡且已被废弃/消费则清除
                        if (_uiState.value.icebreakerThought?.status != UnspokenThoughtEntity.STATUS_PENDING) {
                            icebreakerTimeoutJob?.cancel()
                            _uiState.update { it.copy(icebreakerThought = null) }
                        }
                    }
                    ProactivityLevel.L1_MICRO_EXPRESSION -> {
                        // L1: 微表情由 EmotionEngine 内部冲激驱动，不展示文字气泡
                        if (_uiState.value.icebreakerThought?.status != UnspokenThoughtEntity.STATUS_PENDING) {
                            icebreakerTimeoutJob?.cancel()
                            _uiState.update { it.copy(icebreakerThought = null) }
                        }
                    }
                    ProactivityLevel.L3_LIGHT_NOTIFICATION -> {
                        // L3: 轻通知级别
                    }
                }
            } catch (e: CancellationException) {
                // 协程正常取消，静默退出
            } catch (e: Throwable) {
                // 防御 Room 关闭或跨测试生命周期引起的 SQLite 异常漏出
            }
        }
    }

    /**
     * 刷新未完成念头（清理已过期念头，提取最高紧急度候选作为破冰气泡）。
     * 首帧兜底展示同样收编进主动总线：经协调器首帧裁决并计入每日配额，
     * 不再直接旁路展示（评审中等#4）；协调器缺省时回退旧的直读行为。
     */
    fun refreshIcebreaker(nowTs: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            try {
                unspokenThoughtDao.discardExpiredThoughts(nowTs)
                val coordinator = proactiveCoordinator
                val chosenThought = if (coordinator != null) {
                    val result = coordinator.coordinateFirstFrame(nowTs)
                    if (result.level == ProactivityLevel.L2_ICEBREAKER_BUBBLE) result.thought else null
                } else {
                    unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1).firstOrNull()
                }
                _uiState.update { it.copy(icebreakerThought = chosenThought) }
                scheduleIcebreakerTimeout(chosenThought)
            } catch (e: CancellationException) {
                // 协程正常取消，静默退出
            } catch (e: Throwable) {
                // 忽略底层异常
            }
        }
    }

    /**
     * 用户关闭/忽略破冰气泡，标记为已废弃并回传负向反馈增加退避惩罚
     */
    fun dismissIcebreaker(thought: UnspokenThoughtEntity, nowMs: Long = System.currentTimeMillis()) {
        icebreakerTimeoutJob?.cancel()
        viewModelScope.launch {
            proactiveCoordinator?.onUserFeedback(UserFeedback.DISMISSED, thought.id, nowMs)
            unspokenThoughtDao.markDiscarded(thought.id)
            _uiState.update { state ->
                if (state.icebreakerThought?.id == thought.id) {
                    state.copy(icebreakerThought = null)
                } else {
                    state
                }
            }
        }
    }

    /**
     * 用户点击破冰气泡接茬，标记已消费并回传正向反馈削减退避惩罚，展开面板并将念头填入输入栏
     */
    fun onIcebreakerClicked(thought: UnspokenThoughtEntity, nowMs: Long = System.currentTimeMillis()) {
        icebreakerTimeoutJob?.cancel()
        viewModelScope.launch {
            proactiveCoordinator?.onUserFeedback(UserFeedback.CLICKED, thought.id, nowMs)
            unspokenThoughtDao.markConsumed(thought.id, nowMs)
            _uiState.update { state ->
                state.copy(
                    isExpanded = true,
                    icebreakerThought = null,
                    quickInputText = thought.thought
                )
            }
        }
    }

    /**
     * 破冰气泡展示超时自然隐退 (弱负面反馈)
     */
    fun onIcebreakerIgnored(thought: UnspokenThoughtEntity, nowMs: Long = System.currentTimeMillis()) {
        icebreakerTimeoutJob?.cancel()
        viewModelScope.launch {
            proactiveCoordinator?.onUserFeedback(UserFeedback.IGNORED, thought.id, nowMs)
            _uiState.update { state ->
                if (state.icebreakerThought?.id == thought.id) {
                    state.copy(icebreakerThought = null)
                } else {
                    state
                }
            }
        }
    }

    fun onQuickInputChanged(text: String) {
        _uiState.update { it.copy(quickInputText = text) }
    }

    fun toggleExpanded() {
        _uiState.update { it.copy(isExpanded = !it.isExpanded) }
    }

    fun setExpanded(expanded: Boolean) {
        _uiState.update { it.copy(isExpanded = expanded) }
    }

    /**
     * 发送快速轻量消息并打字机流式回传（支持多轮历史、持久化、工具调用与记忆抽取）
     */
    fun sendQuickMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || _uiState.value.isStreaming) return

        _uiState.update {
            it.copy(
                isStreaming = true,
                streamingReply = "",
                quickInputText = "",
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                // 1. 本地持久化用户输入消息
                val now = System.currentTimeMillis()
                val userEntity = ChatDialogueEntity(
                    sessionId = OVERLAY_SESSION_ID,
                    role = ChatDialogueEntity.ROLE_USER,
                    content = trimmed,
                    timestamp = now
                )
                chatDialogueDao?.insert(userEntity)

                // 2. 加载最近历史多轮会话
                val recentDialogues = chatDialogueDao?.getRecentBySession(OVERLAY_SESSION_ID, limit = 6) ?: emptyList()
                val history = recentDialogues
                    .filter { it.content != trimmed || it.timestamp != now }
                    .reversed()
                    .map { ChatMessage(role = it.role, content = it.content) }

                val currentMood = emotionEngine.currentMood
                val dynamicContext = memoryManager.assembleDynamicContext(
                    query = trimmed,
                    moodValence = currentMood.defaultVector.valence
                )

                val userMsg = ChatMessage(role = "user", content = trimmed)
                val assembledMessages = phiMiddleware.assemble(
                    dynamicContext = dynamicContext,
                    historyMessages = history + userMsg,
                    moodState = currentMood,
                    charName = "Lirica",
                    includeToolPersonaContract = toolAgent != null
                )

                val effectiveConfig = byokRepository?.getEffectiveLlmConfig() ?: llmConfig
                if (effectiveConfig.apiKey.isBlank()) {
                    // 2026-09-27 审计 P0 修复：无 Key 时不再静默吞掉，给出用户可见错误态
                    _uiState.update {
                        it.copy(
                            isStreaming = false,
                            errorMessage = "尚未配置 API Key，请打开主应用完成入门向导或前往设置配置"
                        )
                    }
                    return@launch
                }

                // 3. 多轮流式打字机累积与系统工具闭环
                var conversationMessages = assembledMessages
                var iteration = 0
                val priorRoundsText = StringBuilder()
                var finalText = ""

                while (iteration < MAX_TOOL_ITERATIONS) {
                    iteration++
                    val accumulated = StringBuilder()
                    val toolAssembler = ToolCallAssembler()

                    llmClient.streamChat(
                        config = effectiveConfig,
                        messages = conversationMessages,
                        tools = toolAgent?.declaredTools
                    ).collect { event ->
                        when (event) {
                            is LlmStreamEvent.Token -> {
                                accumulated.append(event.text)
                                val cur = if (priorRoundsText.isNotEmpty()) "$priorRoundsText\n$accumulated" else accumulated.toString()
                                _uiState.update { it.copy(streamingReply = cur) }
                            }
                            is LlmStreamEvent.ToolCallChunk -> {
                                toolAssembler.appendChunk(event)
                            }
                            is LlmStreamEvent.Error -> {
                                throw event.throwable
                            }
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

                        val assistantMsg = ChatMessage(
                            role = "assistant",
                            content = roundContent.ifBlank { null },
                            toolCalls = completedCalls
                        )

                        val toolResults = toolAgent.executeToolCalls(completedCalls)
                        val toolMessages = toolResults.map { it.toChatMessage() }
                        conversationMessages = conversationMessages + assistantMsg + toolMessages
                    } else {
                        finalText = if (priorRoundsText.isNotEmpty()) {
                            if (accumulated.isNotEmpty()) "$priorRoundsText\n$accumulated" else priorRoundsText.toString()
                        } else {
                            accumulated.toString()
                        }
                        break
                    }
                }

                val filtered = humannessFilter.filter(finalText, currentMood)
                _uiState.update {
                    it.copy(
                        streamingReply = filtered.filteredText,
                        isStreaming = false
                    )
                }

                // 4. 持久化助手回复
                val assistantEntity = ChatDialogueEntity(
                    sessionId = OVERLAY_SESSION_ID,
                    role = ChatDialogueEntity.ROLE_ASSISTANT,
                    content = filtered.filteredText,
                    timestamp = System.currentTimeMillis()
                )
                chatDialogueDao?.insert(assistantEntity)

                // 5. 触发长期记忆抽取
                memoryExtractor?.maybeExtract(trimmed, filtered.filteredText)

                // 6. 情绪动态冲激（D22 情绪混合架构）
                val dynamicStimulus = emotionEngine.evaluateTextStimulus(trimmed, filtered.filteredText)
                emotionEngine.applyStimulus(dynamicStimulus)
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isStreaming = false) }
                throw e
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(
                        isStreaming = false,
                        errorMessage = e.message ?: "通信异常"
                    )
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
