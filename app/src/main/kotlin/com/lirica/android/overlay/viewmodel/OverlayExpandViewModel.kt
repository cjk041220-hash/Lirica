package com.lirica.android.overlay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lirica.android.BuildConfig
import com.lirica.android.automation.agent.NativeToolAgent
import com.lirica.android.automation.coordinator.TaskExecutionState
import com.lirica.android.automation.feedback.ActionVisualFeedback
import com.lirica.android.automation.feedback.EmbodiedState
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
import com.lirica.android.network.ToolCallParser
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
import kotlinx.coroutines.flow.catch
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
        // 与主 App 聊天界面对齐为微信式单一连贯流 "default_session"
        const val OVERLAY_SESSION_ID = "default_session"
        const val MAX_TOOL_ITERATIONS = 5
        const val DEFAULT_ICEBREAKER_TIMEOUT_MS = 15_000L
    }

    private var icebreakerTimeoutJob: Job? = null
    private var quickMessageJob: Job? = null

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
        if (chatDialogueDao != null) {
            viewModelScope.launch {
                try {
                    chatDialogueDao.getRecentBySessionFlow(OVERLAY_SESSION_ID, limit = 3)
                        .catch { /* 防御 Room 关闭或跨测试生命周期引起的 SQLite 异常漏出 */ }
                        .collect { list ->
                            _uiState.update { it.copy(recentMessages = list) }
                        }
                } catch (e: CancellationException) {
                    // 协程正常取消
                } catch (e: Throwable) {
                    // 防御 Room 关闭或跨测试生命周期引起的 SQLite 异常漏出
                }
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
                    unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1, nowMs = nowTs).firstOrNull()
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
     * 用户轻触屏幕空白处，收起当前展示的破冰气泡
     */
    fun dismissCurrentIcebreaker() {
        val current = _uiState.value.icebreakerThought ?: return
        dismissIcebreaker(current)
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

                // 1.5 开环闭环核销：用户这轮提到的事项立即核销，并撤回念头池对应提醒
                runCatching { proactiveCoordinator?.resolveOpenLoopsMentionedIn(trimmed) }

                // 2. 加载最近历史多轮会话（排除刚插入的当前条目，保持时间自然正序）
                val recentDialogues = chatDialogueDao?.getRecentBySession(OVERLAY_SESSION_ID, limit = 6) ?: emptyList()
                val history = recentDialogues
                    .filter { it.content != trimmed || it.timestamp != now }
                    .map { ChatMessage(role = it.role, content = it.content) }

                // 2.5 检索双轨记忆并记录命中统计
                val currentMood = emotionEngine.currentMood
                val userMemories = runCatching {
                    memoryManager.retrieve(
                        query = trimmed,
                        topK = 5,
                        moodValence = currentMood.defaultVector.valence
                    )
                }.getOrDefault(emptyList())

                if (userMemories.isNotEmpty()) {
                    val todayDate = java.time.LocalDate.now().toString()
                    val nowTs = System.currentTimeMillis()
                    userMemories.forEach { memory ->
                        runCatching {
                            memoryManager.recordMemoryHit(memory.id, todayDate, nowTs)
                            memoryManager.markMemoryUsed(memory.id)
                        }
                    }
                }

                val dynamicContext = memoryManager.assembleDynamicContext(
                    query = trimmed,
                    moodValence = currentMood.defaultVector.valence,
                    charName = "Lirica",
                    preRecalledUserMemories = userMemories.takeIf { it.isNotEmpty() }
                )

                val userMsg = ChatMessage(role = "user", content = trimmed)
                val turnCount = phiMiddleware.recordTurn(OVERLAY_SESSION_ID)
                val assembledMessages = phiMiddleware.assemble(
                    dynamicContext = dynamicContext,
                    historyMessages = history + userMsg,
                    moodState = currentMood,
                    sessionId = OVERLAY_SESSION_ID,
                    turnCount = turnCount,
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
                                val cleanSnippet = ToolCallParser.cleanDisplayText(accumulated.toString())
                                val cur = if (priorRoundsText.isNotEmpty()) {
                                    if (cleanSnippet.isNotBlank()) "$priorRoundsText\n$cleanSnippet" else priorRoundsText.toString()
                                } else {
                                    cleanSnippet
                                }
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

                    val openAiCalls = toolAssembler.getCompletedToolCalls()
                    val rawRoundContent = accumulated.toString()
                    val (cleanedRoundContent, embeddedCalls) = ToolCallParser.extract(rawRoundContent)
                    val completedCalls = if (openAiCalls.isNotEmpty()) openAiCalls else embeddedCalls

                    if (completedCalls.isNotEmpty() && toolAgent != null) {
                        val roundContent = cleanedRoundContent
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
                            if (cleanedRoundContent.isNotEmpty()) "$priorRoundsText\n$cleanedRoundContent" else priorRoundsText.toString()
                        } else {
                            cleanedRoundContent
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

    private var actionAutoDismissJob: Job? = null

    /**
     * 响应底层具身视觉反馈总线 (ActionFeedbackHub) 事件
     */
    fun onActionFeedback(event: ActionVisualFeedback) {
        when (event) {
            is ActionVisualFeedback.BubbleState -> {
                _uiState.update {
                    it.copy(
                        embodiedState = event.state,
                        actionSpeechText = event.speech ?: it.actionSpeechText
                    )
                }
            }
            is ActionVisualFeedback.StepProgress -> {
                _uiState.update {
                    it.copy(
                        actionStatusText = "第 ${event.stepIndex}/${event.totalSteps} 步: ${event.description}",
                        isActionRecovering = event.isRecovering,
                        isActionRunning = true,
                        isActionCompletedOrFailed = false,
                        embodiedState = if (event.isRecovering) EmbodiedState.OBSERVING else EmbodiedState.ACTING
                    )
                }
            }
            is ActionVisualFeedback.MacroReplayProgress -> {
                _uiState.update {
                    it.copy(
                        actionStatusText = "第 ${event.stepIndex}/${event.totalSteps} 步(经验回放)",
                        isActionRunning = true,
                        isActionCompletedOrFailed = false,
                        embodiedState = EmbodiedState.REPLAYING
                    )
                }
            }
            is ActionVisualFeedback.TouchTrace -> {
                // 触控尾迹事件
            }
        }
    }

    /**
     * 响应任务代行总体状态流转 (TaskExecutionState)
     */
    fun onTaskExecutionStateChanged(state: TaskExecutionState) {
        when (state) {
            is TaskExecutionState.Planning -> {
                actionAutoDismissJob?.cancel()
                _uiState.update {
                    it.copy(
                        isActionRunning = true,
                        isActionCompletedOrFailed = false,
                        isActionRecovering = false,
                        embodiedState = EmbodiedState.THINKING,
                        actionStatusText = "正在规划: ${state.userGoal.take(16)}...",
                        actionSpeechText = null
                    )
                }
            }
            is TaskExecutionState.ExecutingStep -> {
                actionAutoDismissJob?.cancel()
                _uiState.update {
                    it.copy(
                        isActionRunning = true,
                        isActionCompletedOrFailed = false,
                        isActionRecovering = state.isRecovering,
                        embodiedState = if (state.isRecovering) EmbodiedState.OBSERVING else EmbodiedState.ACTING,
                        actionStatusText = if (state.isRecovering) {
                            "⚠️ 自愈重试: ${state.actionDescription}"
                        } else {
                            "⚡ 第 ${state.stepIndex} 步: ${state.actionDescription}"
                        }
                    )
                }
            }
            is TaskExecutionState.Completed -> {
                val msg = state.message.ifBlank { "已成功完成任务！" }
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        isActionCompletedOrFailed = true,
                        isActionRecovering = false,
                        embodiedState = EmbodiedState.SUCCESS,
                        actionStatusText = "任务完成",
                        actionSpeechText = "✅ $msg"
                    )
                }
                scheduleActionAutoDismiss(4500L)
            }
            is TaskExecutionState.Failed -> {
                val honest = state.honestNarrative ?: "任务受阻: ${state.errorReason}"
                _uiState.update {
                    it.copy(
                        isActionRunning = false,
                        isActionCompletedOrFailed = true,
                        isActionRecovering = false,
                        embodiedState = EmbodiedState.FAILED,
                        actionStatusText = "执行受阻",
                        actionSpeechText = "❌ $honest"
                    )
                }
                scheduleActionAutoDismiss(6000L)
            }
            is TaskExecutionState.AwaitingUserConfirmation -> {
                _uiState.update {
                    it.copy(
                        isActionRunning = true,
                        embodiedState = EmbodiedState.THINKING,
                        actionStatusText = "等待草稿确认"
                    )
                }
            }
            TaskExecutionState.Idle -> {
                if (!_uiState.value.isActionCompletedOrFailed) {
                    _uiState.update {
                        it.copy(
                            isActionRunning = false,
                            isActionRecovering = false,
                            embodiedState = EmbodiedState.IDLE,
                            actionStatusText = null
                        )
                    }
                }
            }
        }
    }

    /**
     * 手动关闭/收起行动话语弹泡
     */
    fun dismissActionSpeech() {
        actionAutoDismissJob?.cancel()
        _uiState.update {
            it.copy(
                actionSpeechText = null,
                isActionCompletedOrFailed = false,
                actionStatusText = null,
                embodiedState = if (it.isActionRunning) it.embodiedState else EmbodiedState.IDLE
            )
        }
    }

    private fun scheduleActionAutoDismiss(delayMs: Long) {
        actionAutoDismissJob?.cancel()
        actionAutoDismissJob = viewModelScope.launch {
            try {
                delay(delayMs)
                _uiState.update {
                    it.copy(
                        actionSpeechText = null,
                        isActionCompletedOrFailed = false,
                        actionStatusText = null,
                        embodiedState = if (it.isActionRunning) it.embodiedState else EmbodiedState.IDLE
                    )
                }
            } catch (e: CancellationException) {
                // 协程取消
            }
        }
    }

    /**
     * 用户主动打断当前代行/卡住任务
     * 响应用户指令，取消底层调度并弹出 Lirica 的年上微抱怨或疑惑话语
     */
    fun interruptCurrentAction(onCancelCoordinator: () -> Unit = {}) {
        quickMessageJob?.cancel()
        quickMessageJob = null
        onCancelCoordinator()
        val complaints = listOf(
            "……欸？突然拉住我干嘛，我刚刚正要按下去呢。好吧，那你自己来。",
            "怎么突然按停我了？嫌我动作慢呀……那这步留给你啦。",
            "被你一把拉住了……怎么啦，点错地方了吗？",
            "突然按住我的手……好吧，看来你有更好的打算，交给你了。"
        )
        val text = complaints.random()
        actionAutoDismissJob?.cancel()
        _uiState.update {
            it.copy(
                isStreaming = false,
                streamingReply = text,
                isActionRunning = false,
                isActionCompletedOrFailed = true,
                isActionRecovering = false,
                embodiedState = EmbodiedState.USER_INTERRUPTED,
                actionStatusText = "已打断",
                actionSpeechText = text
            )
        }
        chatDialogueDao?.let { dao ->
            viewModelScope.launch {
                runCatching {
                    dao.insert(
                        ChatDialogueEntity(
                            sessionId = OVERLAY_SESSION_ID,
                            role = ChatDialogueEntity.ROLE_ASSISTANT,
                            content = text,
                            timestamp = System.currentTimeMillis()
                        )
                    )
                }
            }
        }
        scheduleActionAutoDismiss(5500L)
    }

}
