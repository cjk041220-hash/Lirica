package com.lirica.android.overlay.viewmodel

import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.model.MoodState

/**
 * 悬浮伴生体快速展开面板 UI 状态
 */
data class OverlayExpandUiState(
    val isExpanded: Boolean = false,
    val icebreakerThought: UnspokenThoughtEntity? = null,
    val quickInputText: String = "",
    val isStreaming: Boolean = false,
    val streamingReply: String = "",
    val currentMood: MoodState = MoodState.CALM,
    val errorMessage: String? = null
)
