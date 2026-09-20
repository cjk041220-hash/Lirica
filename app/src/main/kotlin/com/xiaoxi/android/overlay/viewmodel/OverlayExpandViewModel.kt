package com.xiaoxi.android.overlay.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoxi.android.BuildConfig
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
import com.xiaoxi.android.persona.HumannessFilter
import com.xiaoxi.android.persona.PhiMiddleware
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
    private val llmConfig: LlmConfig = LlmConfig(
        apiKey = BuildConfig.DEEPSEEK_API_KEY,
        provider = LlmProvider.DEEPSEEK,
        model = "deepseek-chat"
    )
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
     * 刷新未完成念头（清理已过期念头，提取最高紧急度候选作为破冰气泡）
     */
    fun refreshIcebreaker(nowTs: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            unspokenThoughtDao.discardExpiredThoughts(nowTs)
            val topList = unspokenThoughtDao.getTopUrgentPendingThoughts(limit = 1)
            _uiState.update { it.copy(icebreakerThought = topList.firstOrNull()) }
        }
    }

    /**
     * 用户关闭/忽略破冰气泡，标记为已废弃
     */
    fun dismissIcebreaker(thought: UnspokenThoughtEntity) {
        viewModelScope.launch {
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
     * 用户点击破冰气泡，标记已消费，展开面板并将念头填入输入栏
     */
    fun onIcebreakerClicked(thought: UnspokenThoughtEntity) {
        viewModelScope.launch {
            unspokenThoughtDao.markConsumed(thought.id, System.currentTimeMillis())
            _uiState.update { state ->
                state.copy(
                    isExpanded = true,
                    icebreakerThought = null,
                    quickInputText = thought.thought
                )
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
                    charName = "小汐"
                )

                val accumulated = StringBuilder()
                llmClient.streamChat(llmConfig, assembledMessages).collect { event ->
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
