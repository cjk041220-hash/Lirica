package com.lirica.android.overlay.viewmodel

import com.lirica.android.automation.feedback.EmbodiedState
import com.lirica.android.database.entity.ChatDialogueEntity
import com.lirica.android.database.entity.UnspokenThoughtEntity
import com.lirica.android.emotion.model.MoodState

/**
 * 悬浮伴生体快速展开面板与伴随状态 UI 状态
 */
data class OverlayExpandUiState(
    val isExpanded: Boolean = false,
    val icebreakerThought: UnspokenThoughtEntity? = null,
    val quickInputText: String = "",
    val isStreaming: Boolean = false,
    val streamingReply: String = "",
    val currentMood: MoodState = MoodState.CALM,
    val errorMessage: String? = null,
    // 悬浮窗与 App 统一会话同步：最近 2~3 句消息
    val recentMessages: List<ChatDialogueEntity> = emptyList(),
    // 具身伴随状态流（行动中/卡住自愈/完成/用户接管）
    val embodiedState: EmbodiedState = EmbodiedState.IDLE,
    val actionStatusText: String? = null,
    val actionSpeechText: String? = null,
    val isActionRecovering: Boolean = false,
    val isActionRunning: Boolean = false,
    val isActionCompletedOrFailed: Boolean = false
)
