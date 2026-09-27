package com.xiaoxi.android.overlay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoxi.android.BuildConfig
import com.xiaoxi.android.common.settings.ByokSettingsRepository
import com.xiaoxi.android.database.dao.UnspokenThoughtDao
import com.xiaoxi.android.database.entity.UnspokenThoughtEntity
import com.xiaoxi.android.database.memory.DualTrackMemoryManager
import com.xiaoxi.android.emotion.EmotionEngine
import com.xiaoxi.android.emotion.model.EmotionVector
import com.xiaoxi.android.emotion.model.MoodState
import com.xiaoxi.android.network.XiaoxiLlmClient
import com.xiaoxi.android.network.model.ChatMessage
import com.xiaoxi.android.network.model.LlmConfig
import com.xiaoxi.android.network.model.LlmProvider
import com.xiaoxi.android.network.model.LlmStreamEvent
import com.xiaoxi.android.perception.model.PerceptionSnapshot
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
import com.xiaoxi.android.proactivity.ProactiveBubbleCoordinator
import com.xiaoxi.android.proactivity.ProactivityLevel
import com.xiaoxi.android.proactivity.UserFeedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 悬浮伴生体展开面板与破冰念头协调 ViewModel
 */
class OverlayExpandViewModel(
    private val unspokenThoughtDao: UnspokenThoughtDao,
    private val emotionEngine: EmotionEngine,
    private val llmClient: XiaoxiLlmClient,
    private val memoryManager: DualTrackMemoryManager,
    private val phiMiddleware: PhiMiddleware = PhiMiddleware(),
    private val humannessFilter: HumannessFilter = HumannessFilter(),
    val proactiveCoordinator: ProactiveBubbleCoordinator? = null,
    private val llmConfig: LlmConfig = LlmConfig(
        apiKey = BuildConfig.DEEPSEEK_API_KEY,
        provider = LlmProvider.DEEPSEEK,
        model = "deepseek-chat"
    ),
    val byokRepository: ByokSettingsRepository? = null
) : ViewModel() {

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
            val result = coordinator.coordinate(snapshot, nowMs)
            when (result.level) {
                ProactivityLevel.L2_ICEBREAKER_BUBBLE -> {
                    _uiState.update { it.copy(icebreakerThought = result.thought) }
                }
                ProactivityLevel.L0_SILENT -> {
                    // L0: 静默更新内部动力学，若已有破冰气泡且已被废弃/消费则清除
                    if (_uiState.value.icebreakerThought?.status != UnspokenThoughtEntity.STATUS_PENDING) {
                        _uiState.update { it.copy(icebreakerThought = null) }
                    }
                }
                ProactivityLevel.L1_MICRO_EXPRESSION -> {
                    // L1: 微表情由 EmotionEngine 内部冲激驱动，不展示文字气泡
                    if (_uiState.value.icebreakerThought?.status != UnspokenThoughtEntity.STATUS_PENDING) {
                        _uiState.update { it.copy(icebreakerThought = null) }
                    }
                }
                ProactivityLevel.L3_LIGHT_NOTIFICATION -> {
                    // L3: 轻通知级别
                }
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
            unspokenThoughtDao.discardExpiredThoughts(nowTs)
            val coordinator = proactiveCoordinator
            if (coordinator != null) {
                val result = coordinator.coordinateFirstFrame(nowTs)
                _uiState.update {
                    it.copy(
                        icebreakerThought = if (result.level == ProactivityLevel.L2_ICEBREAKER_BUBBLE) {
                            result.thought
                        } else {
                            null
                        }
                    )
                }
            } else {
                val topList = unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1)
                _uiState.update { it.copy(icebreakerThought = topList.firstOrNull()) }
            }
        }
    }

    /**
     * 用户关闭/忽略破冰气泡，标记为已废弃并回传负向反馈增加退避惩罚
     */
    fun dismissIcebreaker(thought: UnspokenThoughtEntity, nowMs: Long = System.currentTimeMillis()) {
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
     * 发送快速轻量消息并打字机流式回传
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
                val currentMood = emotionEngine.currentMood
                val dynamicContext = memoryManager.assembleDynamicContext(
                    query = trimmed,
                    moodValence = currentMood.defaultVector.valence
                )

                val userMsg = ChatMessage(role = "user", content = trimmed)
                val assembledMessages = phiMiddleware.assemble(
                    dynamicContext = dynamicContext,
                    historyMessages = listOf(userMsg),
                    moodState = currentMood,
                    charName = "Lirica"
                )

                val accumulated = StringBuilder()
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

                llmClient.streamChat(effectiveConfig, assembledMessages).collect { event ->
                    when (event) {
                        is LlmStreamEvent.Token -> {
                            accumulated.append(event.text)
                            _uiState.update { it.copy(streamingReply = accumulated.toString()) }
                        }
                        is LlmStreamEvent.Error -> {
                            throw event.throwable
                        }
                        else -> {}
                    }
                }

                val raw = accumulated.toString()
                val filtered = humannessFilter.filter(raw, currentMood)
                _uiState.update {
                    it.copy(
                        streamingReply = filtered.filteredText,
                        isStreaming = false
                    )
                }

                // 微冲激情绪引擎
                emotionEngine.applyStimulus(EmotionVector(0.05f, 0.05f))
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
